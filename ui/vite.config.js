import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  build: {
    emptyOutDir: true,
    outDir: fileURLToPath(new URL("../src/main/resources/console", import.meta.url)),
    lib: {
      entry: fileURLToPath(new URL("./src/index.js", import.meta.url)),
      name: "redirects",
      formats: ["iife"],
      fileName: () => "main.js",
      cssFileName: "style"
    },
    rollupOptions: {
      output: {
        assetFileNames: (assetInfo) => assetInfo.name === "style.css"
          ? "style.css"
          : "assets/[name]-[hash][extname]"
      }
    }
  }
});
