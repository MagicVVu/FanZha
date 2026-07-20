import argparse
import sys
import time
from typing import Any, Dict, List, Optional

import requests


# Match application.yml: chroma.collection-name
DEFAULT_COLLECTION_NAME = "anti_fraud_news"
DEFAULT_BASE_URL = "http://localhost:8000"


def _configure_stdout_utf8() -> None:
    """
    Ensure Windows console can print all Unicode characters.
    """
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        # Best-effort only; if not supported, prints may still get truncated by console encoding.
        pass


def short_text(text: Any, max_len: int = 180) -> str:
    if text is None:
        return ""
    t = str(text).replace("\n", " ").strip()
    return t if len(t) <= max_len else (t[:max_len] + "...")


def get_identity(base_url: str) -> Dict[str, Any]:
    r = requests.get(f"{base_url}/api/v2/auth/identity", timeout=10)
    r.raise_for_status()
    return r.json()


def list_collections(base_url: str, tenant: str, database: str, limit: int = 200) -> List[Dict[str, Any]]:
    r = requests.get(
        f"{base_url}/api/v2/tenants/{tenant}/databases/{database}/collections",
        params={"limit": limit, "offset": 0},
        timeout=20,
    )
    r.raise_for_status()
    return r.json()


def get_collection_records(
    base_url: str,
    tenant: str,
    database: str,
    collection_id: str,
    limit: int,
    offset: int,
) -> Dict[str, Any]:
    payload = {
        "limit": limit,
        "offset": offset,
        "include": ["documents", "metadatas"],
    }
    r = requests.post(
        f"{base_url}/api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/get",
        json=payload,
        timeout=60,
    )
    r.raise_for_status()
    return r.json()


def main() -> int:
    _configure_stdout_utf8()
    parser = argparse.ArgumentParser(description="Query Chroma records (documents + metadatas).")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--collection", default=DEFAULT_COLLECTION_NAME)
    parser.add_argument("--limit", type=int, default=50, help="Max records to print")
    parser.add_argument("--page-size", type=int, default=25, help="Pagination page size")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    collection_name = args.collection
    max_to_print = max(1, args.limit)
    page_size = max(1, args.page_size)

    # Wait for Chroma readiness (v2 identity)
    last_err: Optional[str] = None
    tenant = "default_tenant"
    database = "default_database"
    collection_id = collection_name
    for _ in range(30):
        try:
            ident = get_identity(base_url)
            tenant = ident.get("tenant") or tenant
            databases = ident.get("databases") or [database]
            database = databases[0] if databases else database

            cols = list_collections(base_url, tenant, database, limit=200)
            target = next((c for c in cols if c.get("name") == collection_name), None)
            if not target:
                print(f"Collection not found by name: {collection_name}")
                print("Available collections:")
                for c in cols:
                    print(f"- name={c.get('name')} id={c.get('id')}")
                return 2

            collection_id = target.get("id") or collection_name
            break
        except Exception as e:
            last_err = str(e)
            print(f"[waiting] Chroma not ready yet: {last_err}")
            time.sleep(1)
    else:
        print(f"Chroma connection failed: {last_err}")
        return 1

    # Page through records and print a small sample
    total_printed = 0
    page_offset = 0
    idx = 0
    print("=" * 100)
    print(f"Collection: {collection_name}")
    print(f"Collection ID: {collection_id}")
    print(f"Tenant: {tenant}, Database: {database}")
    print("=" * 100)

    while total_printed < max_to_print:
        resp = get_collection_records(
            base_url=base_url,
            tenant=tenant,
            database=database,
            collection_id=collection_id,
            limit=min(page_size, max_to_print - total_printed),
            offset=page_offset,
        )
        ids = resp.get("ids", []) or []
        docs = resp.get("documents", []) or []
        metas = resp.get("metadatas", []) or []

        if not ids:
            break

        for i in range(len(ids)):
            meta = metas[i] if i < len(metas) and metas[i] is not None else {}
            doc = docs[i] if i < len(docs) else ""
            idx += 1
            print(f"\n[Record {idx}]")
            print(f"ID: {ids[i]}")
            print(f"标题: {short_text(meta.get('title', ''))}")
            print(f"来源: {meta.get('source', '')}")
            print(f"置信度: {meta.get('confidence', '')}")
            print(f"URL: {short_text(meta.get('url', ''))}")
            print(f"文本预览: {short_text(doc)}")
            print("-" * 100)

        total_printed += len(ids)
        page_offset += len(ids)

        if len(ids) < page_size:
            break

    print(f"\nPrinted {total_printed} record(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
