"""
LXB Auto Map Builder - VLM 推理引擎

基于 OpenAI 兼容 API 的视觉语言模型，支持：
- Object Detection: 检测 UI 元素
- OCR: 识别文本
- Caption: 生成页面描述
"""

import io
import json
import os
import re
import base64
import hashlib
import time
from dataclasses import dataclass
from typing import List, Optional, Dict

from .models import VLMDetection, VLMPageResult, BBox


# =============================================================================
# 配置
# =============================================================================

@dataclass
class VLMConfig:
    """VLM API 配置"""
    api_base_url: str = ""
    api_key: str = ""
    model_name: str = "qwen-vl-plus"
    timeout: int = 120

    # 功能开关
    enable_od: bool = True
    enable_ocr: bool = True
    enable_caption: bool = True

    # 缓存
    cache_enabled: bool = True
    max_cache_size: int = 100


_global_config: Optional[VLMConfig] = None


def get_config() -> VLMConfig:
    """获取全局配置"""
    global _global_config
    if _global_config is None:
        _global_config = VLMConfig(
            api_base_url=os.getenv('VLM_API_BASE_URL', ''),
            api_key=os.getenv('VLM_API_KEY', ''),
            model_name=os.getenv('VLM_MODEL_NAME', 'qwen-vl-plus')
        )
    return _global_config


def set_config(config: VLMConfig):
    """设置全局配置"""
    global _global_config
    _global_config = config


# =============================================================================
# VLM 引擎
# =============================================================================

class VLMEngine:
    """OpenAI 兼容 API VLM 引擎"""

    # 提示词 - 只识别核心导航和功能元素
    _PROMPT_OD = """分析这张手机 App 截图，只识别**页面级别的导航和功能入口**。

**必须识别**（通常在页面顶部、底部或固定位置）：
- 顶部导航栏：返回按钮、标题、菜单、搜索框
- 底部导航栏：首页、消息、购物车、我的等 Tab
- 顶部 Tab 切换：如"关注"、"推荐"、"热门"等分类标签
- 搜索框/搜索按钮
- 悬浮按钮：如发布、客服等

**不要识别**（这些是内容，不是导航）：
- 商品卡片、商品图片、商品价格
- 信息流中的任何内容
- 广告横幅、促销活动卡片
- 列表中的每一项
- 任何滚动区域内的内容

**坐标**：像素坐标 [x1, y1, x2, y2]

返回 JSON：
```json
{
  "elements": [
    {"label": "tab", "bbox": [55, 180, 165, 241], "text": "关注"},
    {"label": "nav_item", "bbox": [100, 2700, 200, 2772], "text": "首页"}
  ]
}
```

label: tab, nav_item, button, icon, input, search
只返回 JSON，最多 30 个元素。"""

    _PROMPT_OCR = ""  # 禁用单独的 OCR，OD 已包含文本

    _PROMPT_CAPTION = """一句话描述这是什么 App 的什么页面。"""

    def __init__(self, config: Optional[VLMConfig] = None):
        self.config = config or get_config()
        self._client = None
        self._cache: Dict[str, VLMPageResult] = {}
        self.stats = {"total_inferences": 0, "cache_hits": 0, "total_time_ms": 0.0}

    def _get_client(self):
        """延迟创建 OpenAI 客户端"""
        if self._client is None:
            from openai import OpenAI

            if not self.config.api_base_url:
                raise ValueError("VLM API URL 未配置")
            if not self.config.api_key:
                raise ValueError("VLM API Key 未配置")

            self._client = OpenAI(
                base_url=self.config.api_base_url,
                api_key=self.config.api_key,
                timeout=self.config.timeout
            )
        return self._client

    def _call_api(self, image_bytes: bytes, prompt: str) -> str:
        """调用 VLM API"""
        client = self._get_client()
        image_base64 = base64.b64encode(image_bytes).decode('utf-8')

        response = client.chat.completions.create(
            model=self.config.model_name,
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}},
                    {"type": "text", "text": prompt}
                ]
            }],
            max_tokens=4096
        )
        return response.choices[0].message.content

    def _parse_json(self, response: str) -> Dict:
        """从响应中提取 JSON"""
        # 尝试提取 ```json ... ```
        match = re.search(r'```json\s*(.*?)\s*```', response, re.DOTALL)
        if match:
            response = match.group(1)
        else:
            # 尝试提取 { ... }
            match = re.search(r'\{.*\}', response, re.DOTALL)
            if match:
                response = match.group(0)

        try:
            return json.loads(response)
        except json.JSONDecodeError:
            return {}

    def _detect_coord_format(self, bbox: List[int], image_width: int, image_height: int) -> str:
        """
        自动检测坐标格式
        - 如果坐标值都 <= 1000，可能是归一化坐标
        - 如果坐标值接近图片尺寸，是像素坐标
        """
        max_val = max(bbox)
        if max_val <= 1000:
            # 可能是归一化坐标，但也可能是小图的像素坐标
            # 如果图片尺寸远大于 1000，则认为是归一化坐标
            if image_width > 1200 or image_height > 1200:
                return "normalized"
        return "pixel"

    def _to_pixel_coords(self, bbox: List[int], image_width: int, image_height: int) -> BBox:
        """将坐标转换为像素坐标（自动检测格式）"""
        coord_format = self._detect_coord_format(bbox, image_width, image_height)

        if coord_format == "normalized":
            # 归一化坐标 (0-1000) 转像素
            x1 = int(bbox[0] * image_width / 1000)
            y1 = int(bbox[1] * image_height / 1000)
            x2 = int(bbox[2] * image_width / 1000)
            y2 = int(bbox[3] * image_height / 1000)
        else:
            # 已经是像素坐标，直接使用
            x1, y1, x2, y2 = int(bbox[0]), int(bbox[1]), int(bbox[2]), int(bbox[3])

        return (x1, y1, x2, y2)

    def _normalize_to_pixel(self, bbox: List[int], image_width: int, image_height: int) -> BBox:
        """将归一化坐标 (0-1000) 转换为像素坐标"""
        x1 = int(bbox[0] * image_width / 1000)
        y1 = int(bbox[1] * image_height / 1000)
        x2 = int(bbox[2] * image_width / 1000)
        y2 = int(bbox[3] * image_height / 1000)
        return (x1, y1, x2, y2)

    def _iou(self, box1: BBox, box2: BBox) -> float:
        """计算 IoU"""
        x1, y1 = max(box1[0], box2[0]), max(box1[1], box2[1])
        x2, y2 = min(box1[2], box2[2]), min(box1[3], box2[3])

        if x2 <= x1 or y2 <= y1:
            return 0.0

        inter = (x2 - x1) * (y2 - y1)
        area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
        return inter / (area1 + area2 - inter) if (area1 + area2 - inter) > 0 else 0.0

    def _run_od(self, image_bytes: bytes, image_width: int, image_height: int) -> List[VLMDetection]:
        """目标检测 - 自动检测坐标格式并转换为像素坐标"""
        try:
            data = self._parse_json(self._call_api(image_bytes, self._PROMPT_OD))
            detections = []

            for elem in data.get('elements', []):
                bbox = elem.get('bbox', [0, 0, 0, 0])
                if len(bbox) == 4:
                    # 自动检测坐标格式并转换
                    pixel_bbox = self._to_pixel_coords(bbox, image_width, image_height)
                    detections.append(VLMDetection(
                        bbox=pixel_bbox,
                        label=elem.get('label', 'unknown'),
                        confidence=1.0,
                        ocr_text=elem.get('text', '')
                    ))
            return detections
        except Exception as e:
            print(f"[VLMEngine] OD failed: {e}")
            return []

    def _run_ocr(self, image_bytes: bytes, detections: List[VLMDetection], image_width: int, image_height: int) -> List[VLMDetection]:
        """OCR 文本识别 - 自动检测坐标格式"""
        try:
            data = self._parse_json(self._call_api(image_bytes, self._PROMPT_OCR))

            for item in data.get('texts', []):
                bbox = item.get('bbox', [0, 0, 0, 0])
                text = item.get('text', '')

                if len(bbox) == 4 and text:
                    # 自动检测坐标格式并转换
                    pixel_bbox = self._to_pixel_coords(bbox, image_width, image_height)

                    # 尝试匹配已有检测框
                    for det in detections:
                        if not det.ocr_text and self._iou(det.bbox, pixel_bbox) > 0.5:
                            det.ocr_text = text
                            break
                    else:
                        detections.append(VLMDetection(
                            bbox=pixel_bbox,
                            label="text",
                            confidence=1.0,
                            ocr_text=text
                        ))
            return detections
        except Exception as e:
            print(f"[VLMEngine] OCR failed: {e}")
            return detections

    def _run_caption(self, image_bytes: bytes) -> str:
        """页面描述"""
        try:
            return self._call_api(image_bytes, self._PROMPT_CAPTION).strip()
        except Exception as e:
            print(f"[VLMEngine] Caption failed: {e}")
            return ""

    def infer(self, screenshot_bytes: bytes) -> VLMPageResult:
        """执行 VLM 推理"""
        from PIL import Image

        start = time.time()
        image_hash = hashlib.md5(screenshot_bytes).hexdigest()

        # 缓存检查
        if self.config.cache_enabled and image_hash in self._cache:
            self.stats["cache_hits"] += 1
            return self._cache[image_hash]

        # 获取图像尺寸
        image = Image.open(io.BytesIO(screenshot_bytes))
        width, height = image.width, image.height

        # 执行推理 - 只做 OD（已包含文本），跳过单独的 OCR
        detections = self._run_od(screenshot_bytes, width, height) if self.config.enable_od else []
        # OCR 已禁用，OD prompt 已包含文本识别
        caption = self._run_caption(screenshot_bytes) if self.config.enable_caption else ""

        # 构建结果
        result = VLMPageResult(
            page_caption=caption,
            detections=detections,
            inference_time_ms=(time.time() - start) * 1000,
            image_size=(image.width, image.height)
        )

        # 更新统计和缓存
        self.stats["total_inferences"] += 1
        self.stats["total_time_ms"] += result.inference_time_ms

        if self.config.cache_enabled:
            if len(self._cache) >= self.config.max_cache_size:
                next(iter(self._cache.popitem()))  # FIFO 淘汰
            self._cache[image_hash] = result

        return result

    def is_available(self) -> bool:
        """检查 VLM 是否可用"""
        try:
            return bool(self.config.api_base_url and self.config.api_key)
        except Exception:
            return False

    def clear_cache(self):
        """清空缓存"""
        self._cache.clear()
