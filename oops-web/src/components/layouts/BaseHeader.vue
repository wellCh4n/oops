<script lang="ts" setup>
import { toggleDark } from '~/composables';
</script>

<template>
  <el-menu class="el-menu-demo" mode="horizontal">
    <el-menu-item index="1">oops</el-menu-item>
    <el-menu-item index="2">应用</el-menu-item>
    <el-menu-item index="3">流水线</el-menu-item>
    <el-menu-item index="4">配置</el-menu-item>
    <el-menu-item index="5">系统设置</el-menu-item>
<!--    <el-menu-item h="full" @click="toggleDark()">-->
<!--      <button class="border-none w-full bg-transparent cursor-pointer" style="height: var(&#45;&#45;ep-menu-item-height);">-->
<!--        <i inline-flex i="dark:ep-moon ep-sunny" />-->
<!--      </button>-->
<!--    </el-menu-item>-->
  </el-menu>
</template>
