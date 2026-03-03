package run.halo.redirects.listener;

import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.halo.app.plugin.SettingFetcher;
import run.halo.redirects.config.RedirectSettings;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectSettingsUpdatedListener
    implements ApplicationListener<RedirectSettingsUpdatedEvent> {
    private static final Logger log =
        LoggerFactory.getLogger(RedirectSettingsUpdatedListener.class);

    private final SettingFetcher settingFetcher;

    public RedirectSettingsUpdatedListener(SettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    @Override
    public void onApplicationEvent(RedirectSettingsUpdatedEvent event) {
        var settings = settingFetcher.fetch("basic", RedirectSettings.class).orElse(null);
        RedirectRuleRegistry.reload(settings);
        log.info("[redirects] loaded {} active redirect rule(s)", RedirectRuleRegistry.size());
    }
}
