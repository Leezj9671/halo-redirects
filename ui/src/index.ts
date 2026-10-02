import { definePlugin } from "@halo-dev/console-shared";
import { markRaw } from "vue";
import RulesTab from "./views/RulesTab.vue";

// The rules UI lives in a tab of this plugin's own detail page instead of a sidebar route,
// so it only loads where it is used and cannot affect other plugins' menu entries.
export default definePlugin({
  components: {},
  routes: [],
  extensionPoints: {
    "plugin:self:tabs:create": () => [
      {
        id: "redirect-rules",
        label: "规则管理",
        component: markRaw(RulesTab),
      },
    ],
  },
});
