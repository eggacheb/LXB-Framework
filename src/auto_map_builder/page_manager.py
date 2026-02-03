"""
LXB Auto Map Builder v2 - 页面管理器

负责：
- 页面结构哈希计算
- 页面去重判断
- 页面状态管理
"""

import hashlib
from typing import List, Dict, Optional, Set

from .models import FusedNode, PageState


class PageManager:
    """页面管理器"""

    def __init__(self, similarity_threshold: float = 0.85):
        """
        Args:
            similarity_threshold: 页面相似度阈值
        """
        self.similarity_threshold = similarity_threshold

        # 已知页面 {page_id: PageState}
        self.pages: Dict[str, PageState] = {}

        # Activity -> page_ids 映射
        self.activity_pages: Dict[str, Set[str]] = {}

        # 结构哈希 -> page_id 映射 (用于快速去重)
        self.hash_to_page: Dict[str, str] = {}

    def compute_structure_hash(self, nodes: List[FusedNode]) -> str:
        """
        计算页面结构哈希 (用于去重)

        哈希内容:
        - 节点数量
        - 每个节点的: class_name + 相对位置 + 交互类型

        不包含:
        - 具体文本内容 (动态变化)
        - 绝对坐标 (滚动会变)

        Args:
            nodes: 融合节点列表

        Returns:
            16 位哈希字符串
        """
        if not nodes:
            return "empty_page_0000"

        # 按位置排序节点 (从上到下，从左到右)
        sorted_nodes = sorted(nodes, key=lambda n: (n.bounds[1], n.bounds[0]))

        hash_parts = []
        for node in sorted_nodes:
            # 相对位置 (归一化到 0-100 的网格)
            rel_x = node.bounds[0] // 10
            rel_y = node.bounds[1] // 10

            # 交互类型标记
            interact = ""
            if node.clickable:
                interact += "C"
            if node.editable:
                interact += "E"
            if node.scrollable:
                interact += "S"

            # 类名简写 (取最后一段，最多 10 字符)
            class_short = node.class_name.split(".")[-1][:10]

            hash_parts.append(f"{class_short}:{rel_x},{rel_y}:{interact}")

        content = "|".join(hash_parts)
        return hashlib.md5(content.encode()).hexdigest()[:16]

    def generate_page_id(self, activity: str, structure_hash: str) -> str:
        """
        生成页面 ID

        格式: {ActivityShortName}_{hash[:8]}

        Args:
            activity: Activity 全名
            structure_hash: 结构哈希

        Returns:
            页面 ID
        """
        activity_short = activity.split(".")[-1] if activity else "Unknown"
        return f"{activity_short}_{structure_hash[:8]}"

    def is_known_page(self, activity: str, structure_hash: str) -> Optional[str]:
        """
        检查是否是已知页面

        Args:
            activity: Activity 名称
            structure_hash: 结构哈希

        Returns:
            如果是已知页面，返回 page_id；否则返回 None
        """
        # 方法1: 精确哈希匹配
        if structure_hash in self.hash_to_page:
            return self.hash_to_page[structure_hash]

        # 方法2: 同 Activity 下的相似页面检查
        # (可选，用于处理轻微布局变化)
        # 暂时只用精确匹配

        return None

    def register_page(self, page: PageState) -> bool:
        """
        注册新页面

        Args:
            page: 页面状态

        Returns:
            True 如果是新页面，False 如果已存在
        """
        # 检查是否已存在
        existing = self.is_known_page(page.activity, page.structure_hash)
        if existing:
            # 更新访问计数
            self.pages[existing].visit_count += 1
            return False

        # 注册新页面
        self.pages[page.page_id] = page
        self.hash_to_page[page.structure_hash] = page.page_id

        # 更新 Activity 映射
        if page.activity not in self.activity_pages:
            self.activity_pages[page.activity] = set()
        self.activity_pages[page.activity].add(page.page_id)

        return True

    def get_page(self, page_id: str) -> Optional[PageState]:
        """获取页面"""
        return self.pages.get(page_id)

    def get_pages_by_activity(self, activity: str) -> List[PageState]:
        """获取指定 Activity 的所有页面"""
        page_ids = self.activity_pages.get(activity, set())
        return [self.pages[pid] for pid in page_ids if pid in self.pages]

    def get_all_pages(self) -> List[PageState]:
        """获取所有页面"""
        return list(self.pages.values())

    def get_stats(self) -> Dict:
        """获取统计信息"""
        return {
            "total_pages": len(self.pages),
            "total_activities": len(self.activity_pages),
            "pages_per_activity": {
                act: len(pids) for act, pids in self.activity_pages.items()
            }
        }


def is_duplicate_node(node: FusedNode, existing_nodes: List[FusedNode]) -> bool:
    """
    检查节点是否与已有节点重复

    判断标准:
    - 相同的 resource_id (如果有)
    - 或者相同的 bounds

    Args:
        node: 待检查节点
        existing_nodes: 已有节点列表

    Returns:
        True 如果是重复节点
    """
    for existing in existing_nodes:
        # 如果都有 resource_id，比较 resource_id
        if node.resource_id and existing.resource_id:
            if node.resource_id == existing.resource_id:
                return True

        # 比较 bounds (完全相同)
        if node.bounds == existing.bounds:
            return True

    return False
