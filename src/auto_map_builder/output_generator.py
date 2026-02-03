"""
LXB Auto Map Builder v2 - JSON 输出生成器

生成：
- app_overview.json: 应用概览 (页面列表+描述)
- pages/{page_id}.json: 页面详情 (节点+跳转)
"""

import os
import json
from datetime import datetime
from typing import Dict, List, Optional

from .models import ExplorationResult, PageState, Transition, FusedNode


class OutputGenerator:
    """JSON 输出生成器"""

    def __init__(self, output_dir: str = "./maps"):
        self.output_dir = output_dir

    def save(self, result: ExplorationResult, save_screenshots: bool = False):
        """
        保存探索结果

        Args:
            result: 探索结果
            save_screenshots: 是否保存截图
        """
        # 创建输出目录
        package_dir = os.path.join(self.output_dir, result.package)
        pages_dir = os.path.join(package_dir, "pages")
        os.makedirs(pages_dir, exist_ok=True)

        if save_screenshots:
            screenshots_dir = os.path.join(package_dir, "screenshots")
            os.makedirs(screenshots_dir, exist_ok=True)

        # 生成 app_overview.json
        overview = self._generate_overview(result)
        overview_path = os.path.join(package_dir, "app_overview.json")
        self._write_json(overview_path, overview)

        # 生成每个页面的 JSON
        for page_id, page in result.pages.items():
            page_data = self._generate_page_json(page, result.transitions)
            page_path = os.path.join(pages_dir, f"{page_id}.json")
            self._write_json(page_path, page_data)

        print(f"[OutputGenerator] 已保存到: {package_dir}")
        print(f"  - app_overview.json")
        print(f"  - pages/ ({len(result.pages)} 个页面)")

    def _generate_overview(self, result: ExplorationResult) -> Dict:
        """生成 app_overview.json"""
        # 构建页面摘要列表
        pages_summary = []
        for page_id, page in result.pages.items():
            pages_summary.append({
                "page_id": page_id,
                "activity": page.activity,
                "description": page.page_description,
                "node_count": len(page.nodes),
                "clickable_count": len(page.clickable_nodes),
                "editable_count": len(page.editable_nodes),
                "scrollable_count": len(page.scrollable_nodes)
            })

        # 构建跳转摘要列表
        transitions_summary = []
        for trans in result.transitions:
            # 获取触发节点的描述
            trigger_desc = ""
            if trans.target_node_id:
                from_page = result.pages.get(trans.from_page_id)
                if from_page:
                    node = next(
                        (n for n in from_page.nodes if n.node_id == trans.target_node_id),
                        None
                    )
                    if node:
                        trigger_desc = node.semantic_text or node.vlm_label or node.class_name.split(".")[-1]

            transitions_summary.append({
                "from": trans.from_page_id,
                "to": trans.to_page_id,
                "action": trans.action_type,
                "trigger": trigger_desc
            })

        # 去重跳转 (相同的 from-to-action 只保留一个)
        seen = set()
        unique_transitions = []
        for t in transitions_summary:
            key = (t["from"], t["to"], t["action"])
            if key not in seen:
                seen.add(key)
                unique_transitions.append(t)

        return {
            "package": result.package,
            "exploration_time": datetime.now().isoformat(),
            "total_pages": len(result.pages),
            "total_transitions": len(unique_transitions),
            "stats": {
                "exploration_seconds": round(result.exploration_time_seconds, 2),
                "total_actions": result.total_actions,
                "vlm_inferences": result.vlm_inference_count,
                "vlm_time_ms": round(result.vlm_total_time_ms, 2)
            },
            "pages": pages_summary,
            "transitions": unique_transitions
        }

    def _generate_page_json(
        self,
        page: PageState,
        all_transitions: List[Transition]
    ) -> Dict:
        """生成单个页面的 JSON"""
        # 获取从该页面出发的跳转
        page_transitions = [
            t for t in all_transitions if t.from_page_id == page.page_id
        ]

        # 构建节点到跳转的映射
        node_transitions: Dict[str, List[Dict]] = {}
        for trans in page_transitions:
            if trans.target_node_id:
                if trans.target_node_id not in node_transitions:
                    node_transitions[trans.target_node_id] = []
                node_transitions[trans.target_node_id].append({
                    "to_page_id": trans.to_page_id,
                    "action": trans.action_type
                })

        # 构建节点列表
        nodes_data = []
        for node in page.nodes:
            node_data = {
                "node_id": node.node_id,
                "bounds": list(node.bounds),
                "center": list(node.center),

                # XML 属性
                "class_name": node.class_name,
                "text": node.text,
                "resource_id": node.resource_id,
                "content_desc": node.content_desc,

                # 交互属性
                "clickable": node.clickable,
                "editable": node.editable,
                "scrollable": node.scrollable,

                # VLM 增强
                "vlm_label": node.vlm_label,
                "vlm_ocr_text": node.vlm_ocr_text,
                "iou_score": round(node.iou_score, 3) if node.iou_score else None
            }

            # 添加跳转信息
            if node.node_id in node_transitions:
                node_data["transitions"] = node_transitions[node.node_id]

            nodes_data.append(node_data)

        # 识别可滚动区域
        scrollable_regions = []
        for node in page.scrollable_nodes:
            scrollable_regions.append({
                "node_id": node.node_id,
                "bounds": list(node.bounds),
                "direction": "vertical"  # 默认垂直滚动
            })

        return {
            "page_id": page.page_id,
            "activity": page.activity,
            "package": page.package,
            "description": page.page_description,
            "structure_hash": page.structure_hash,
            "visit_count": page.visit_count,
            "nodes": nodes_data,
            "scrollable_regions": scrollable_regions
        }

    def _write_json(self, path: str, data: Dict):
        """写入 JSON 文件"""
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)


def generate_map_json(result: ExplorationResult) -> Dict:
    """
    生成兼容旧版的 map.json 格式

    Args:
        result: 探索结果

    Returns:
        map.json 格式的字典
    """
    generator = OutputGenerator()
    return generator._generate_overview(result)
