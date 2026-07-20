from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    # 未配置模型密钥时保留规则引擎和本地知识库降级能力。
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
