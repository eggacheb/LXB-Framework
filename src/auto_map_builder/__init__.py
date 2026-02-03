"""
LXB Auto Map Builder v2

基于 VLM + XML 融合的 Android 应用自动建图系统

主要组件:
- VLMEngine: OpenAI 兼容 API 视觉语言模型推理 (支持 Qwen-VL 等)
- FusionEngine: XML-VLM IoU 空间匹配融合
- PageManager: 页面去重与结构哈希
- Explorer: BFS 广度优先探索引擎
- OutputGenerator: JSON 输出生成

使用示例:
    from src.auto_map_builder import AutoMapBuilder, ExplorationConfig, set_config
    from src.auto_map_builder.vlm_engine import VLMConfig
    from src.lxb_link.client import LXBLinkClient

    # 配置 VLM API
    set_config(VLMConfig(
        api_base_url="https://api.example.com/v1",
        api_key="your-api-key",
        model_name="qwen-vl-plus"
    ))

    # 连接设备
    client = LXBLinkClient("192.168.1.100", 12345)
    client.connect()
    client.handshake()

    # 配置探索
    config = ExplorationConfig(
        max_pages=30,
        max_depth=5
    )

    # 执行探索
    builder = AutoMapBuilder(client, config)
    result = builder.explore("com.example.app")

    # 保存结果
    builder.save("./maps")
"""

from .models import (
    ExplorationConfig,
    ExplorationResult,
    PageState,
    FusedNode,
    XMLNode,
    Transition,
    VLMDetection,
    VLMPageResult
)
from .vlm_engine import VLMEngine, VLMConfig, get_config, set_config
from .fusion_engine import FusionEngine, parse_xml_nodes
from .page_manager import PageManager
from .explorer import Explorer
from .output_generator import OutputGenerator, generate_map_json


class AutoMapBuilder:
    """
    自动建图器主类

    封装了完整的探索流程，提供简洁的 API
    """

    def __init__(
        self,
        client,
        config: ExplorationConfig = None,
        log_callback=None
    ):
        """
        Args:
            client: LXB-Link 客户端实例
            config: 探索配置
            log_callback: 日志回调函数
        """
        self.client = client
        self.config = config or ExplorationConfig()
        self.log_callback = log_callback

        self._explorer = None
        self._result = None

    def explore(self, package_name: str) -> ExplorationResult:
        """
        执行应用探索

        Args:
            package_name: 应用包名

        Returns:
            探索结果
        """
        self._explorer = Explorer(
            self.client,
            self.config,
            self.log_callback
        )
        self._result = self._explorer.explore(package_name)
        return self._result

    def save(self, output_dir: str = None):
        """
        保存探索结果

        Args:
            output_dir: 输出目录，默认使用配置中的目录
        """
        if not self._result:
            raise RuntimeError("请先执行 explore() 方法")

        output_dir = output_dir or self.config.output_dir
        generator = OutputGenerator(output_dir)
        generator.save(self._result, self.config.save_screenshots)

    def get_result(self) -> ExplorationResult:
        """获取探索结果"""
        return self._result

    def generate_overview_json(self) -> dict:
        """生成应用概览 JSON"""
        if not self._result:
            raise RuntimeError("请先执行 explore() 方法")
        return generate_map_json(self._result)


__all__ = [
    # 主类
    "AutoMapBuilder",

    # 配置和结果
    "ExplorationConfig",
    "ExplorationResult",

    # 数据结构
    "PageState",
    "FusedNode",
    "XMLNode",
    "Transition",
    "VLMDetection",
    "VLMPageResult",

    # VLM
    "VLMEngine",
    "VLMConfig",
    "get_config",
    "set_config",

    # 其他组件
    "FusionEngine",
    "PageManager",
    "Explorer",
    "OutputGenerator",

    # 工具函数
    "parse_xml_nodes",
    "generate_map_json"
]
