import "./style.css";
import RedirectsManagerPage from "./RedirectsManagerPage.vue";

export default {
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "redirects",
        name: "RedirectsManager",
        component: RedirectsManagerPage,
        meta: {
          title: "Redirects",
          searchable: true,
          menu: {
            name: "重定向规则",
            priority: 90
          }
        }
      }
    }
  ]
};
