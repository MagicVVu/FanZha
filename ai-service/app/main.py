from fastapi import FastAPI
from app.routers.assistant import router as assistant_router
from app.routers.admin_review import router as admin_review_router

app = FastAPI(
    title="反诈通智能分析服务",
    description="提供反诈对话、多模态风险分析、知识库检索与人工复核接口。",
    version="1.0.0",
)

app.include_router(assistant_router, prefix="/api/assistant", tags=["assistant"])
app.include_router(admin_review_router, prefix="/api/admin/review", tags=["admin-review"])

@app.get("/health")
def health():
    return {"ok": True}
