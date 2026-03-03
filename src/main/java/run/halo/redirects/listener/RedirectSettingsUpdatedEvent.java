package run.halo.redirects.listener;

import org.springframework.context.ApplicationEvent;

public class RedirectSettingsUpdatedEvent extends ApplicationEvent {
    public RedirectSettingsUpdatedEvent(Object source) {
        super(source);
    }

    public static RedirectSettingsUpdatedEvent trigger(Object source) {
        return new RedirectSettingsUpdatedEvent(source);
    }
}

