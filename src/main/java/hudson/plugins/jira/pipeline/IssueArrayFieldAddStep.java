package hudson.plugins.jira.pipeline;

import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueField;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.jira.JiraSession;
import hudson.plugins.jira.JiraSite;
import hudson.plugins.jira.Messages;
import hudson.plugins.jira.model.JiraIssueField;
import hudson.plugins.jira.selector.AbstractIssueSelector;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSON;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Issue custom array field value adder
 * 
 * @author Christopher Fraser cfraz89@gmail.com
 * 
 */
public class IssueArrayFieldAddStep extends Builder implements SimpleBuildStep {
    private AbstractIssueSelector issueSelector;

    public AbstractIssueSelector getIssueSelector() {
        return this.issueSelector;
    }

    @DataBoundSetter
    public void setIssueSelector(AbstractIssueSelector issueSelector) {
        this.issueSelector = issueSelector;
    }

    public String fieldId;

    public String getFieldId() {
        return this.fieldId;
    }

    @DataBoundSetter
    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String valueToAdd;

    public String getValueToAdd() { return this.valueToAdd; }

    @DataBoundSetter
    public void setValueToAdd(String value) { this.valueToAdd = value; }

    @DataBoundConstructor
    public IssueArrayFieldAddStep(AbstractIssueSelector issueSelector, String fieldId, String valueToAdd) {
        this.issueSelector = issueSelector;
        this.fieldId = fieldId;
        this.valueToAdd = valueToAdd;
    }

    public String prepareFieldId(String fieldId) {
        String prepared = fieldId;
        if (!prepared.startsWith("customfield_"))
            prepared = "customfield_" + prepared;
        return prepared;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException {
        PrintStream logger = listener.getLogger();

        AbstractIssueSelector selector = issueSelector;
        if (selector == null) {
            logger.println("[JIRA][IssueArrayFieldAddStep] No issue selector found!");
            throw new IOException("[JIRA][IssueArrayFieldAddStep] No issue selector found!");
        }

        JiraSite site = JiraSite.get(run.getParent());
        if (site == null) {
            logger.println(Messages.NoJiraSite());
            run.setResult(Result.FAILURE);
            return;
        }

        JiraSession session = site.getSession();
        if (session == null) {
            logger.println(Messages.NoRemoteAccess());
            run.setResult(Result.FAILURE);
            return;
        }

        Set<String> issues = selector.findIssueIds(run, site, listener);
        if (issues.isEmpty()) {
            logger.println("[JIRA][IssueArrayFieldAddStep] Issue list is empty!");
            return;
        }

        String preparedFieldId = prepareFieldId(fieldId);
        ArrayList<JiraIssueField> fields = new ArrayList<>();
        for (String issueKey : issues) {
            Issue issue = session.getIssue(issueKey);
            if (issue == null) {
                logger.println("Issue " + issueKey + " not found");
                continue;
            }
            IssueField issueField = issue.getField(preparedFieldId);
            if (issueField == null) {
                logger.println("Field " + preparedFieldId + " not found");
                run.setResult(Result.FAILURE);
                return;
            }
            JSONArray fieldValues = (JSONArray) issueField.getValue();
            List<String> lFieldValues = new ArrayList<>();
            if (fieldValues != null) {
                for (int i = 0; i < fieldValues.length(); i++) {
                    try {
                        lFieldValues.add(fieldValues.getString(i));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
            if (!lFieldValues.contains(valueToAdd)) {
                lFieldValues.add(valueToAdd);
            }
            fields.add(new JiraIssueField(preparedFieldId, lFieldValues));
        }

        for (String issue : issues) {
            submitFields(session, issue, fields, logger);
        }
    }

    public void submitFields(JiraSession session, String issueId, List<JiraIssueField> fields, PrintStream logger) {
        try {
            session.addFields(issueId, fields);
        } catch (RestClientException e) {

            if (e.getStatusCode().or(0).equals(404)) {
                logger.println(issueId + " - JIRA issue not found");
            }

            if (e.getStatusCode().or(0).equals(403)) {
                logger.println(issueId
                        + " - Jenkins JIRA user does not have permissions to comment on this issue");
            }

            if (e.getStatusCode().or(0).equals(401)) {
                logger.println(
                        issueId + " - Jenkins JIRA authentication problem");
            }

            logger.println(Messages.FailedToUpdateIssue(issueId));
            logger.println(e.getLocalizedMessage());
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckField_id(@QueryParameter String value) throws IOException, ServletException {
            if (Util.fixNull(value).trim().length() == 0)
                return FormValidation.warning(Messages.JiraIssueFieldUpdater_NoIssueFieldID());
            if (!value.matches("\\d+"))
                return FormValidation.error(Messages.JiraIssueFieldUpdater_NotAtIssueFieldID());
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.JiraIssueArrayFieldAdder_DisplayName();
        }
    }

}
