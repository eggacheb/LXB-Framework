"""
LXB Auto Map Builder v2 - XML-VLM 融合引擎

以 VLM 检测结果为主，通过 IoU 匹配找到对应的 XML 节点，
获取 resource_id 等属性用于后续自动化操作。

核心思路：
1. VLM 识别重要的可交互元素（过滤掉重复列表项等）
2. 根据 VLM 坐标找对应的 XML node
3. 找到就记录 resource_id 等属性，找不到就忽略
"""

from typing import List, Tuple, Optional, Dict
from dataclasses import dataclass

from .models import XMLNode, FusedNode, VLMDetection, VLMPageResult, BBox


@dataclass
class MatchResult:
    """匹配结果"""
    vlm_idx: int
    xml_idx: int
    iou_score: float


class FusionEngine:
    """XML-VLM 融合引擎 - 以 VLM 为主"""

    def __init__(self, iou_threshold: float = 0.3):
        """
        Args:
            iou_threshold: IoU 匹配阈值，低于此值视为不匹配
        """
        self.iou_threshold = iou_threshold

        # 匹配统计
        self.stats = {
            "total_vlm_detections": 0,
            "total_xml_nodes": 0,
            "matched_count": 0,
            "unmatched_vlm": 0
        }

    def compute_iou(self, box1: BBox, box2: BBox) -> float:
        """计算两个边界框的 IoU"""
        x1 = max(box1[0], box2[0])
        y1 = max(box1[1], box2[1])
        x2 = min(box1[2], box2[2])
        y2 = min(box1[3], box2[3])

        if x2 <= x1 or y2 <= y1:
            return 0.0

        intersection = (x2 - x1) * (y2 - y1)
        area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
        union = area1 + area2 - intersection

        return intersection / union if union > 0 else 0.0

    def _find_best_xml_match(
        self,
        vlm_bbox: BBox,
        xml_nodes: List[XMLNode],
        used_xml: set
    ) -> Optional[Tuple[int, float]]:
        """
        为一个 VLM 检测框找到最佳匹配的 XML 节点

        Args:
            vlm_bbox: VLM 检测框
            xml_nodes: XML 节点列表
            used_xml: 已使用的 XML 节点索引集合

        Returns:
            (xml_idx, iou_score) 或 None
        """
        best_idx = -1
        best_iou = 0.0

        for i, xml_node in enumerate(xml_nodes):
            if i in used_xml:
                continue

            iou = self.compute_iou(vlm_bbox, xml_node.bounds)
            if iou > best_iou and iou >= self.iou_threshold:
                best_iou = iou
                best_idx = i

        if best_idx >= 0:
            return (best_idx, best_iou)
        return None

    def fuse(
        self,
        xml_nodes: List[XMLNode],
        vlm_result: VLMPageResult
    ) -> List[FusedNode]:
        """
        执行 VLM 主导的融合

        策略：
        1. 遍历每个 VLM 检测结果
        2. 为每个 VLM 检测找最佳匹配的 XML 节点
        3. 找到匹配：创建融合节点，包含 XML 的 resource_id 等属性
        4. 找不到匹配：忽略该 VLM 检测（可能是误检或纯视觉元素）

        Args:
            xml_nodes: XML UI 节点列表
            vlm_result: VLM 推理结果

        Returns:
            融合节点列表（只包含成功匹配的）
        """
        vlm_detections = vlm_result.detections

        # 更新统计
        self.stats["total_vlm_detections"] += len(vlm_detections)
        self.stats["total_xml_nodes"] += len(xml_nodes)

        fused_nodes: List[FusedNode] = []
        used_xml: set = set()

        for vlm_idx, vlm_det in enumerate(vlm_detections):
            # 为 VLM 检测找最佳 XML 匹配
            match = self._find_best_xml_match(vlm_det.bbox, xml_nodes, used_xml)

            if match is None:
                # 找不到匹配，忽略
                self.stats["unmatched_vlm"] += 1
                continue

            xml_idx, iou_score = match
            used_xml.add(xml_idx)
            xml_node = xml_nodes[xml_idx]

            # 创建融合节点
            fused = FusedNode(
                node_id=f"ui_{len(fused_nodes):03d}",
                bounds=xml_node.bounds,  # 使用 XML 的精确坐标
                class_name=xml_node.class_name,
                text=xml_node.text,
                resource_id=xml_node.resource_id,
                content_desc=xml_node.content_desc,
                clickable=xml_node.clickable,
                editable=xml_node.editable,
                scrollable=xml_node.scrollable,
                vlm_label=vlm_det.label,
                vlm_ocr_text=vlm_det.ocr_text,
                iou_score=iou_score
            )
            fused_nodes.append(fused)
            self.stats["matched_count"] += 1

        return fused_nodes

    def get_stats(self) -> Dict:
        """获取统计信息"""
        stats = self.stats.copy()
        if stats["total_vlm_detections"] > 0:
            stats["match_rate"] = stats["matched_count"] / stats["total_vlm_detections"]
        else:
            stats["match_rate"] = 0.0
        return stats

    def reset_stats(self):
        """重置统计信息"""
        self.stats = {
            "total_vlm_detections": 0,
            "total_xml_nodes": 0,
            "matched_count": 0,
            "unmatched_vlm": 0
        }


def parse_xml_nodes(raw_nodes: List[Dict]) -> List[XMLNode]:
    """
    将 dump_actions 返回的原始节点数据转换为 XMLNode 对象

    Args:
        raw_nodes: dump_actions 返回的节点列表

    Returns:
        XMLNode 对象列表
    """
    xml_nodes: List[XMLNode] = []

    for i, node in enumerate(raw_nodes):
        bounds = node.get("bounds", [0, 0, 0, 0])
        if len(bounds) != 4:
            continue

        xml_node = XMLNode(
            node_id=f"node_{i:03d}",
            bounds=(bounds[0], bounds[1], bounds[2], bounds[3]),
            class_name=node.get("class", node.get("class_name", "")),
            text=node.get("text", ""),
            resource_id=node.get("resource_id", ""),
            content_desc=node.get("content_desc", ""),
            clickable=node.get("clickable", False),
            editable=node.get("editable", False),
            scrollable=node.get("scrollable", False)
        )
        xml_nodes.append(xml_node)

    return xml_nodes
