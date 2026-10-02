/// <reference types="vite/client" />

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<object, object, unknown>;
  export default component;
}

// FormKit is registered globally by the Halo console.
declare module "vue" {
  interface ComponentCustomProperties {
    $formkit: { submit: (formId: string) => void };
  }
}

export {};
