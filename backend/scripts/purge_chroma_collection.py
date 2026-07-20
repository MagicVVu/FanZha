import argparse
import sys
import time
from typing import Any, Dict, List, Optional

import requests


DEFAULT_BASE_URL = "http://localhost:8000"
DEFAULT_COLLECTION_NAME = "anti_fraud_news"


def _configure_stdout_utf8() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


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


def get_ids_page(
    base_url: str,
    tenant: str,
    database: str,
    collection_id: str,
    limit: int,
    offset: int,
) -> List[str]:
    # Use GET records endpoint but request only ids via include=[]
    payload = {"limit": limit, "offset": offset, "include": []}
    r = requests.post(
        f"{base_url}/api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/get",
        json=payload,
        timeout=60,
    )
    r.raise_for_status()
    data = r.json()
    return data.get("ids", []) or []


def delete_ids_batch(
    base_url: str,
    tenant: str,
    database: str,
    collection_id: str,
    ids: List[str],
) -> int:
    payload = {"ids": ids}
    r = requests.post(
        f"{base_url}/api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/delete",
        json=payload,
        timeout=60,
    )
    r.raise_for_status()
    data = r.json() if r.text else {}
    deleted = data.get("deleted")
    return int(deleted) if deleted is not None else 0


def main() -> int:
    _configure_stdout_utf8()

    parser = argparse.ArgumentParser(description="Purge ALL records from a Chroma collection (keep collection structure).")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--collection", default=DEFAULT_COLLECTION_NAME, help="Collection name")
    parser.add_argument("--page-size", type=int, default=200, help="How many IDs to fetch per page")
    parser.add_argument("--batch-size", type=int, default=100, help="How many IDs to delete per request")
    parser.add_argument("--dry-run", action="store_true", help="Only count IDs, do not delete")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    collection_name = args.collection
    page_size = max(1, args.page_size)
    batch_size = max(1, args.batch_size)

    # Resolve tenant/database + collection id
    last_err: Optional[str] = None
    tenant = "default_tenant"
    database = "default_database"
    collection_id = collection_name
    for _ in range(30):
        try:
            ident = get_identity(base_url)
            tenant = ident.get("tenant") or tenant
            dbs = ident.get("databases") or [database]
            database = dbs[0] if dbs else database

            cols = list_collections(base_url, tenant, database, limit=500)
            target = next((c for c in cols if c.get("name") == collection_name), None)
            if not target:
                print(f"Collection not found: {collection_name}")
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

    print(f"Target collection: {collection_name}")
    print(f"Tenant: {tenant}, Database: {database}")
    print(f"Collection ID: {collection_id}")

    # Fetch all ids first (stable even while deleting)
    all_ids: List[str] = []
    offset = 0
    while True:
        ids = get_ids_page(base_url, tenant, database, collection_id, limit=page_size, offset=offset)
        if not ids:
            break
        all_ids.extend(ids)
        offset += len(ids)
        if len(ids) < page_size:
            break
        if offset % 2000 == 0:
            print(f"Fetched ids: {offset}")

    total = len(all_ids)
    print(f"Total records found: {total}")
    if args.dry_run:
        print("Dry-run enabled. No deletion performed.")
        return 0

    deleted_total = 0
    for i in range(0, total, batch_size):
        batch = all_ids[i : i + batch_size]
        deleted = delete_ids_batch(base_url, tenant, database, collection_id, batch)
        deleted_total += deleted
        if (i // batch_size + 1) % 10 == 0 or i + batch_size >= total:
            print(f"Deleted so far: {deleted_total}/{total}")

    print(f"Done. Deleted: {deleted_total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
