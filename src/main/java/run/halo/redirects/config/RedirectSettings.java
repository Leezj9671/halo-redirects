package run.halo.redirects.config;

import java.util.List;

public class RedirectSettings {
    private Boolean enabled;
    private Boolean preserveQueryString;
    private String bulkRules;
    private List<RedirectRule> rules;

    public static class RedirectRule {
        private String fromPath;
        private String toPath;
        private Integer statusCode;
        private String note;
        private String matchType;

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

        public String getMatchType() {
            return matchType;
        }

        public void setMatchType(String matchType) {
            this.matchType = matchType;
        }
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getPreserveQueryString() {
        return preserveQueryString;
    }

    public void setPreserveQueryString(Boolean preserveQueryString) {
        this.preserveQueryString = preserveQueryString;
    }

    public List<RedirectRule> getRules() {
        return rules;
    }

    public void setRules(List<RedirectRule> rules) {
        this.rules = rules;
    }

    public String getBulkRules() {
        return bulkRules;
    }

    public void setBulkRules(String bulkRules) {
        this.bulkRules = bulkRules;
    }
}
