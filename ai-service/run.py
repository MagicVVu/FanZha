import os

import uvicorn

if __name__ == "__main__":
    reload_enabled = os.getenv("UVICORN_RELOAD", "0") == "1"
    host = os.getenv("HOST", "127.0.0.1")
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run("app.main:app", host=host, port=port, reload=reload_enabled)
