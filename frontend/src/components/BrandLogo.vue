/**
 * @file    BrandLogo.vue
 * @module  通用组件
 * @desc    系统品牌标识组件。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 */

<template>
  <svg
    class="brand-logo"
    viewBox="0 0 48 48"
    fill="none"
    :width="size"
    :height="size"
    aria-hidden="true"
  >
    
    <rect x="2" y="2" width="44" height="44" rx="13" fill="currentColor" />

    
    <circle cx="24" cy="24" r="5.6" stroke="#ffffff" stroke-width="3.4" />

    
    <path d="M19.6 19.6 L14.6 14.6 M28.4 19.6 L33.4 14.6 M19.6 28.4 L14.6 33.4 M28.4 28.4 L33.4 33.4"
      stroke="#ffffff" stroke-width="3" stroke-linecap="round" />

    
    <circle cx="13.4" cy="13.4" r="2.9" fill="#ffffff" />
    <circle cx="34.6" cy="13.4" r="2.9" fill="#ffffff" />
    <circle cx="13.4" cy="34.6" r="2.9" fill="#ffffff" />
    <circle cx="34.6" cy="34.6" r="2.9" fill="#ffffff" />
  </svg>
</template>

<script setup>
// 品牌标识：星型拓扑纳管
// 语义：设备纳管 / 拓扑自动上图 / 中心化验证，直接对应软件核心功能
defineProps({
  size: { type: [Number, String], default: 32 }
})
</script>
