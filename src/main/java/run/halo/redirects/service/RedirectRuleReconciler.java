package run.halo.redirects.service;

import org.springframework.stereotype.Component;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.redirects.extension.RedirectRule;

/**
 * Reloads the rules whenever a {@link RedirectRule} is created, changed or deleted, including
 * through Halo's generic extension API.
 */
@Component
public class RedirectRuleReconciler implements Reconciler<Reconciler.Request> {
    private final RedirectRuleReloader reloader;

    public RedirectRuleReconciler(RedirectRuleReloader reloader) {
        this.reloader = reloader;
    }

    @Override
    public Result reconcile(Request request) {
        reloader.requestReload("rule change");
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new RedirectRule()).syncAllOnStart(false).build();
    }
}
