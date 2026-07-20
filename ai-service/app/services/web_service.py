from playwright.async_api import async_playwright
from bs4 import BeautifulSoup

async def extract_text_from_website(url: str):
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page()
        await page.goto(url, wait_until="networkidle", timeout=30000)
        html = await page.content()
        await browser.close()

    soup = BeautifulSoup(html, "html.parser")

    for s in soup(["script", "style", "noscript"]):
        s.extract()

    text = soup.get_text(separator=" ", strip=True)

    # 非严格做法：抓到的正文越长，提取质量越高
    quality = min(len(text) / 2000, 1.0)

    return text[:8000], quality