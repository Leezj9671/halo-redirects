package run.halo.redirects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.redirects.extension.RedirectRule;
import run.halo.redirects.listener.RedirectSettingsUpdatedEvent;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectsPlugin extends BasePlugin {
    private static final Logger log = LoggerFactory.getLogger(RedirectsPlugin.class);

    private final ApplicationEventPublisher eventPublisher;
    private final SchemeManager schemeManager;

    public RedirectsPlugin(PluginContext pluginContext, ApplicationEventPublisher eventPublisher,
        SchemeManager schemeManager) {
        super(pluginContext);
        this.eventPublisher = eventPublisher;
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(RedirectRule.class);
        eventPublisher.publishEvent(RedirectSettingsUpdatedEvent.trigger(this));
        log.info("[redirects] plugin started");
    }

    @Override
    public void stop() {
        RedirectRuleRegistry.clear();
        schemeManager.fetch(GroupVersionKind.fromExtension(RedirectRule.class))
            .ifPresent(schemeManager::unregister);
        log.info("[redirects] plugin stopped");
    }

    @Override
    public void delete() {
        stop();
        log.info("[redirects] plugin deleted");
    }
}
