package com.miyuetech.mimifiles.core.model

/** 列表展示形态。横屏（Windows 资源管理器形态）以 [DETAILS] 为默认。 */
enum class FileViewMode {
    /** 竖屏默认：图标 + 名称 + 副标题。 */
    LIST,

    /** 网格平铺，适合图片/视频目录。 */
    GRID,

    /** 多列详细信息：名称 / 大小 / 修改时间 / 类型。 */
    DETAILS,
}
