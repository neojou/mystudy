package com.neojou.tools.ui.menu

/**
 * Configurable entry for [MyTopMenuBar].
 *
 * Semantics (max **two** levels in the current [MyTopMenuBar] implementation):
 * - [children] **non-empty** → top-level **dropdown** root; only immediate children are shown as menu items
 * - [children] **empty** → top-level **action button**; invokes [onClick]
 * - Nested [children] on a child are ignored (reserved for a future recursive bar)
 *
 * Product-specific labels and actions are supplied by the host app; this type stays free of app packages.
 *
 * @property id Stable identifier for tests/logging (not shown in UI)
 * @property label Visible text
 * @property enabled When false, the control is not interactive
 * @property onClick Leaf action (used when [children] is empty, or for dropdown leaves)
 * @property children Dropdown entries under this root (typically leaves with [onClick])
 */
data class MyTopMenuItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
    val children: List<MyTopMenuItem> = emptyList(),
)
