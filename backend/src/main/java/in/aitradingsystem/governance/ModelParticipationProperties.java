package in.aitradingsystem.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Global fail-closed participation switches. Per-model controls can only further restrict these bounds.
 * Environments not implemented for routine use remain disabled by default.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.governance.model-participation")
public class ModelParticipationProperties {
    private boolean researchEnabled = true;
    private boolean backtestEnabled = true;
    private boolean paperEnabled = true;
    private boolean shadowEnabled;
    private boolean liveAdvisoryEnabled;
    private boolean liveExecutionInfluenceEnabled;

    public boolean isResearchEnabled() {
        return researchEnabled;
    }

    public void setResearchEnabled(boolean researchEnabled) {
        this.researchEnabled = researchEnabled;
    }

    public boolean isBacktestEnabled() {
        return backtestEnabled;
    }

    public void setBacktestEnabled(boolean backtestEnabled) {
        this.backtestEnabled = backtestEnabled;
    }

    public boolean isPaperEnabled() {
        return paperEnabled;
    }

    public void setPaperEnabled(boolean paperEnabled) {
        this.paperEnabled = paperEnabled;
    }

    public boolean isShadowEnabled() {
        return shadowEnabled;
    }

    public void setShadowEnabled(boolean shadowEnabled) {
        this.shadowEnabled = shadowEnabled;
    }

    public boolean isLiveAdvisoryEnabled() {
        return liveAdvisoryEnabled;
    }

    public void setLiveAdvisoryEnabled(boolean liveAdvisoryEnabled) {
        this.liveAdvisoryEnabled = liveAdvisoryEnabled;
    }

    public boolean isLiveExecutionInfluenceEnabled() {
        return liveExecutionInfluenceEnabled;
    }

    public void setLiveExecutionInfluenceEnabled(boolean liveExecutionInfluenceEnabled) {
        this.liveExecutionInfluenceEnabled = liveExecutionInfluenceEnabled;
    }
}
