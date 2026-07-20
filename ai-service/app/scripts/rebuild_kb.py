import argparse
import json
import re
import shutil
from datetime import datetime
from pathlib import Path
from typing import Any

from langchain_qdrant import QdrantVectorStore
from qdrant_client.http.models import Distance, VectorParams

from app.services.kb_service import (
    KB_COLLECTION_NAME,
    KB_EMBED_MODEL,
    KB_MANIFEST_PATH,
    KB_MIN_SCORE,
    KB_QDRANT_PATH,
    KB_QUERY_INSTRUCTION,
    KB_SEED_PATH,
    KB_TOP_K,
    _build_documents,
    _load_seed_data,
    clear_kb_caches,
    compute_seed_sha256,
    get_embeddings,
    get_qdrant_client,
    get_retrieval_runtime_config,
    summarize_seed_items,
)

KB_VERSION_ROOT = Path("app/data/kb_versions")
PROJECT_ROOT = Path(__file__).resolve().parents[2]


def sanitize_tag(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip())
    return cleaned.strip("-")


def build_version(seed_sha256: str, tag: str = "") -> str:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    short_sha = seed_sha256[:8]
    safe_tag = sanitize_tag(tag)
    if safe_tag:
        return f"{timestamp}_{safe_tag}_{short_sha}"
    return f"{timestamp}_{short_sha}"


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def reset_qdrant_store() -> None:
    qdrant_path = KB_QDRANT_PATH.resolve()
    project_root = PROJECT_ROOT.resolve()

    if project_root not in qdrant_path.parents and qdrant_path != project_root:
        raise RuntimeError(f"Refusing to delete qdrant path outside project: {qdrant_path}")

    if qdrant_path.exists():
        shutil.rmtree(qdrant_path)


def update_version_index(index_path: Path, manifest: dict[str, Any]) -> None:
    summary = {
        "build_version": manifest["build_version"],
        "build_tag": manifest.get("build_tag", ""),
        "created_at": manifest["created_at"],
        "collection_name": manifest["collection_name"],
        "seed_sha256": manifest["seed_sha256"],
        "seed_count": manifest["seed_count"],
        "chunk_count": manifest["chunk_count"],
        "actual_points": manifest["actual_points"],
        "seed_summary": manifest["seed_summary"],
    }

    if index_path.exists():
        try:
            items = json.loads(index_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            items = []
    else:
        items = []

    if not isinstance(items, list):
        items = []

    items = [item for item in items if item.get("build_version") != summary["build_version"]]
    items.insert(0, summary)
    write_json(index_path, items)


def main() -> None:
    parser = argparse.ArgumentParser(description="重建本地 Qdrant 反诈知识库。")
    parser.add_argument("--tag", default="", help="可选构建标签，用于区分版本。")
    args = parser.parse_args()

    clear_kb_caches()
    reset_qdrant_store()

    client = get_qdrant_client()
    embeddings = get_embeddings()
    seed_items = _load_seed_data(KB_SEED_PATH)
    docs = _build_documents(seed_items)
    seed_sha256 = compute_seed_sha256(seed_items)
    seed_summary = summarize_seed_items(seed_items)
    version = build_version(seed_sha256, args.tag)

    if client.collection_exists(KB_COLLECTION_NAME):
        client.delete_collection(KB_COLLECTION_NAME)

    vector_size = len(embeddings.embed_query("测试文本"))
    client.create_collection(
        collection_name=KB_COLLECTION_NAME,
        vectors_config=VectorParams(size=vector_size, distance=Distance.COSINE),
    )

    vector_store = QdrantVectorStore(
        client=client,
        collection_name=KB_COLLECTION_NAME,
        embedding=embeddings,
    )
    if docs:
        vector_store.add_documents(docs)

    collection_info = client.get_collection(KB_COLLECTION_NAME)
    actual_points = int(collection_info.points_count or 0)

    manifest = {
        "build_version": version,
        "build_tag": sanitize_tag(args.tag),
        "collection_name": KB_COLLECTION_NAME,
        "seed_path": str(KB_SEED_PATH).replace("\\", "/"),
        "seed_sha256": seed_sha256,
        "seed_count": len(seed_items),
        "seed_summary": seed_summary,
        "chunk_count": len(docs),
        "actual_points": actual_points,
        "vector_dim": vector_size,
        "embed_model": KB_EMBED_MODEL,
        "query_instruction": KB_QUERY_INSTRUCTION,
        "retrieval_config": get_retrieval_runtime_config(),
        "qdrant_path": str(KB_QDRANT_PATH).replace("\\", "/"),
        "top_k_default": KB_TOP_K,
        "min_score": KB_MIN_SCORE,
        "created_at": datetime.now().isoformat(),
    }

    version_dir = KB_VERSION_ROOT / version
    write_json(KB_MANIFEST_PATH, manifest)
    write_json(version_dir / "kb_manifest.json", manifest)
    write_json(version_dir / "faq_seed.snapshot.json", seed_items)
    update_version_index(KB_VERSION_ROOT / "index.json", manifest)

    print(f"知识库构建完成: {KB_COLLECTION_NAME}")
    print(f"构建版本: {version}")
    print(f"种子条数: {len(seed_items)}")
    print(f"切分块数: {len(docs)}")
    print(f"实际向量点数: {actual_points}")
    print(f"向量维度: {vector_size}")
    print(f"manifest: {KB_MANIFEST_PATH}")
    print(f"snapshot: {version_dir}")


if __name__ == "__main__":
    main()
