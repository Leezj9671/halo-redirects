package run.halo.redirects.extension;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * One redirect rule, stored as its own Halo extension so every rule has a stable name (id),
 * can be edited on its own and does not depend on how Halo stores plugin settings.
 */
@GVK(group = RedirectRule.GROUP, version = RedirectRule.VERSION, kind = RedirectRule.KIND,
    plural = "redirectrules", singular = "redirectrule")
public class RedirectRule extends AbstractExtension {
    public static final String GROUP = "redirects.halo.run";
    public static final String VERSION = "v1alpha1";
    public static final String KIND = "RedirectRule";

    private Spec spec;

    public Spec getSpec() {
        return spec;
    }

    public void setSpec(Spec spec) {
        this.spec = spec;
    }

    public static class Spec {
        private String fromPath;
        private String toPath;
        private String matchType;
        private Integer statusCode;
        private String note;
        private Boolean enabled;

        public String getFromPath() {
            return fromPath;
        }

        public void setFromPath(String fromPath) {
            this.fromPath = fromPath;
        }

        public String getToPath() {
            return toPath;
        }

        public void setToPath(String toPath) {
            this.toPath = toPath;
        }

        public String getMatchType() {
            return matchType;
        }

        public void setMatchType(String matchType) {
            this.matchType = matchType;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(Integer statusCode) {
            this.statusCode = statusCode;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
