package run.halo.redirects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.redirects.listener.RedirectSettingsUpdatedEvent;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectsPlugin extends BasePlugin {
    private static final Logger log = LoggerFactory.getLogger(RedirectsPlugin.class);

    private final ApplicationEventPublisher eventPublisher;

    public RedirectsPlugin(PluginContext pluginContext, ApplicationEventPublisher eventPublisher) {
        super(pluginContext);
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void start() {
        eventPublisher.publishEvent(RedirectSettingsUpdatedEvent.trigger(this));
        log.info("[redirects] plugin started");
    }

    @Override
    public void stop() {
        RedirectRuleRegistry.clear();
        log.info("[redirects] plugin stopped");
    }

    @Override
    public void delete() {
        stop();
        log.info("[redirects] plugin deleted");
    }
}
