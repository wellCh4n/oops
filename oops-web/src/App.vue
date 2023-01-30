<template>
  <el-config-provider namespace="ep">
    <BaseHeader />
    <Welcome/>
<!--    <div style="display: flex">-->
<!--&lt;!&ndash;      <BaseSide />&ndash;&gt;-->
<!--      <div>-->
<!--        <img alt="Vue logo" class="element-plus-logo" src="./assets/logo.png" />-->
<!--        <HelloWorld msg="Hello Vue 3.0 + Element Plus + Vite" />-->
<!--      </div>-->
<!--    </div>-->
  </el-config-provider>
  <RouterView/>
</template>

<style>
#app {
  text-align: center;
  color: var(--ep-text-color-primary);
}

.element-plus-logo {
  width: 50%;
}
</style>
<script setup lang="ts">
import Welcome from "~/components/Welcome.vue";
</script>