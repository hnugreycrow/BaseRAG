import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useLayoutStore = defineStore('layout', () => {
  const sidebarCollapsed = ref(false)
  const mobileMenuOpen = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function openMobileMenu() {
    mobileMenuOpen.value = true
  }

  function closeMobileMenu() {
    mobileMenuOpen.value = false
  }

  return {
    sidebarCollapsed,
    mobileMenuOpen,
    toggleSidebar,
    openMobileMenu,
    closeMobileMenu,
  }
})
