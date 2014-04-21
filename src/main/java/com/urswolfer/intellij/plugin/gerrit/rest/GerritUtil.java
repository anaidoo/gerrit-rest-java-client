/*
 * Copyright 2000-2011 JetBrains s.r.o.
 * Copyright 2013 Urs Wolfer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.urswolfer.intellij.plugin.gerrit.rest;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.common.*;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import com.intellij.idea.ActionsBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.util.Consumer;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.gerrit.client.rest.http.GerritRestClientFactory;
import com.urswolfer.gerrit.client.rest.http.HttpStatusException;
import com.urswolfer.intellij.plugin.gerrit.GerritSettings;
import com.urswolfer.intellij.plugin.gerrit.ui.LoginDialog;
import com.urswolfer.intellij.plugin.gerrit.util.NotificationBuilder;
import com.urswolfer.intellij.plugin.gerrit.util.NotificationService;
import com.urswolfer.intellij.plugin.gerrit.util.UrlUtils;
import git4idea.GitUtil;
import git4idea.config.GitVcsApplicationSettings;
import git4idea.config.GitVersion;
import git4idea.i18n.GitBundle;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.HyperlinkEvent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parts based on org.jetbrains.plugins.github.GithubUtil
 *
 * @author Urs Wolfer
 * @author Konrad Dobrzynski
 */
public class GerritUtil {

    @Inject
    private GerritSettings gerritSettings;
    @Inject
    private SslSupport sslSupport;
    @Inject
    private Logger log;
    @Inject
    private NotificationService notificationService;
    @Inject
    private GerritApi gerritClient;
    @Inject
    private GerritRestClientFactory gerritRestClientFactory;
    @Inject
    private ProxyHttpClientBuilderExtension proxyHttpClientBuilderExtension;

    public <T> T accessToGerritWithModalProgress(Project project,
                                                 ThrowableComputable<T, Exception> computable) {
        gerritSettings.preloadPassword();
        return accessToGerritWithModalProgress(project, computable, gerritSettings);
    }

    public <T> T accessToGerritWithModalProgress(Project project,
                                                 ThrowableComputable<T, Exception> computable,
                                                 GerritAuthData gerritAuthData) {
        try {
            return doAccessToGerritWithModalProgress(project, computable);
        } catch (Exception e) {
            if (sslSupport.isCertificateException(e)) {
                if (sslSupport.askIfShouldProceed(gerritAuthData.getHost())) {
                    // retry with the host being already trusted
                    return doAccessToGerritWithModalProgress(project, computable);
                } else {
                    return null;
                }
            }
            throw Throwables.propagate(e);
        }
    }

    private <T> T doAccessToGerritWithModalProgress(final Project project,
                                                    final ThrowableComputable<T, Exception> computable) {
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        ProgressManager.getInstance().run(new Task.Modal(project, "Access to Gerrit", true) {
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result.set(computable.compute());
                } catch (Exception e) {
                    exception.set(e);
                }
            }
        });
        //noinspection ThrowableResultOfMethodCallIgnored
        if (exception.get() == null) {
            return result.get();
        }
        throw Throwables.propagate(exception.get());
    }

    public void postReview(final String changeId,
                           final String revision,
                           final ReviewInput reviewInput,
                           final Project project,
                           final Consumer<Void> consumer) {
        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public Void apply(Void aVoid) {
                try {
                    gerritClient.changes().id(changeId).revision(revision).review(reviewInput);
                } catch (RestApiException e) {
                    notifyError(e, "Failed to post Gerrit review.", project);
                }
                return null;
            }
        };
        accessGerrit(function, consumer, project);
    }

    public void postSubmit(final String changeId,
                           final SubmitInput submitInput,
                           final Project project) {
        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public Void apply(Void aVoid) {
                try {
                    gerritClient.changes().id(changeId).current().submit(submitInput);
                } catch (RestApiException e) {
                    notifyError(e, "Failed to submit Gerrit change.", project);
                }
                return null;
            }
        };
        accessGerrit(function, Consumer.EMPTY_CONSUMER, project);
    }

    public void postAbandon(final String changeId,
                            final AbandonInput abandonInput,
                            final Project project) {
        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public Void apply(Void aVoid) {
                try {
                    gerritClient.changes().id(changeId).abandon(abandonInput);
                } catch (RestApiException e) {
                    notifyError(e, "Failed to abandon Gerrit change.", project);
                }
                return null;
            }
        };
        accessGerrit(function, Consumer.EMPTY_CONSUMER, project);
    }

    /**
     * Star-endpoint added in Gerrit 2.8.
     */
    public void changeStarredStatus(final String changeNr,
                                    final boolean starred,
                                    final Project project) {
        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public Void apply(Void aVoid) {
                try {
                    gerritClient.accounts().self().changeStarredStatus(changeNr, starred);
                } catch (RestApiException e) {
                    notifyError(e, "Failed to star Gerrit change." +
                            "<br/>Not supported for Gerrit instances older than version 2.8.", project);
                }
                return null;
            }
        };
        accessGerrit(function, Consumer.EMPTY_CONSUMER, project);
    }

    public void getChangeReviewed(final String changeId,
                                  final String revision,
                                  final String filePath,
                                  final boolean reviewed,
                                  final Project project) {

        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public Void apply(Void aVoid) {
                try {
                    gerritClient.changes().id(changeId).revision(revision).changeReviewed(filePath, reviewed);
                } catch (RestApiException e) {
                    notifyError(e, "Failed set file review status for Gerrit change.", project);
                }
                return null;
            }
        };
        accessGerrit(function, Consumer.EMPTY_CONSUMER, project);
    }

    public void getChangesToReview(Project project, Consumer<List<ChangeInfo>> consumer) {
        getChanges("is:open+reviewer:self", project, consumer);
    }

    public void getChangesForProject(String query, final Project project, final Consumer<List<ChangeInfo>> consumer) {
        if (!gerritSettings.getListAllChanges()) {
            query = appendQueryStringForProject(project, query);
        }
        getChanges(query, project, consumer);
    }

    public void getChanges(String query, final Project project, final Consumer<List<ChangeInfo>> consumer) {
        String request = formatRequestUrl(query);
        request = appendToUrlQuery(request, "o=LABELS");
        final String finalQuery = request;
        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public List<ChangeInfo> apply(Void aVoid) {
                try {
                    return gerritClient.changes().list(finalQuery);
                } catch (RestApiException e) {
                    notifyError(e, "Failed to get Gerrit changes.", project);
                    return Collections.emptyList();
                }
            }
        };
        accessGerrit(function, consumer, project);
    }

    private String appendQueryStringForProject(Project project, String query) {
        String projectQueryPart = getProjectQueryPart(project);
        query = Joiner.on('+').skipNulls().join(Strings.emptyToNull(query), Strings.emptyToNull(projectQueryPart));
        return query;
    }

    private String formatRequestUrl(String query) {
        if (query.isEmpty()) {
            return "";
        } else {
            return String.format("q=%s", query);
        }
    }

    private String getProjectQueryPart(Project project) {
        List<GitRepository> repositories = GitUtil.getRepositoryManager(project).getRepositories();
        if (repositories.isEmpty()) {
            showAddGitRepositoryNotification(project);
            return "";
        }

        List<GitRemote> remotes = Lists.newArrayList();
        for (GitRepository repository : repositories) {
            remotes.addAll(repository.getRemotes());
        }

        List<String> projectNames = Lists.newArrayList();
        for (GitRemote remote : remotes) {
            for (String repositoryUrl : remote.getUrls()) {
                if (UrlUtils.urlHasSameHost(repositoryUrl, gerritSettings.getHost())) {
                    String projectName = getProjectName(gerritSettings.getHost(), repositoryUrl);
                    if (Strings.isNullOrEmpty(projectName)) {
                        projectNames.add("project:" + projectName);
                    }
                }
            }
        }

        if (projectNames.isEmpty()) {
            return "";
        }
        return String.format("(%s)", Joiner.on("+OR+").join(projectNames));
    }

    private String getProjectName(String repositoryUrl, String url) {
        if (!repositoryUrl.endsWith("/")) {
            repositoryUrl = repositoryUrl + "/";
        }

        String basePath = UrlUtils.createUriFromGitConfigString(repositoryUrl).getPath();
        String path = UrlUtils.createUriFromGitConfigString(url).getPath();

        if (path.length() >= basePath.length()) {
            path = path.substring(basePath.length());
        }

        path = path.replace(".git", ""); // some repositories end their name with ".git"

        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    public void showAddGitRepositoryNotification(final Project project) {
        NotificationBuilder notification = new NotificationBuilder(project, "Insufficient dependencies for Gerrit plugin",
                "Please configure a Git repository.<br/><a href='vcs'>Open Settings</a>")
                .listener(new NotificationListener() {
                    @Override
                    public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                        if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                            if (event.getDescription().equals("vcs")) {
                                ShowSettingsUtil.getInstance().showSettingsDialog(project, ActionsBundle.message("group.VcsGroup.text"));
                            }
                        }
                    }
                });
        notificationService.notifyWarning(notification);
    }

    public void getChangeDetails(final String changeNr, final Project project, final Consumer<ChangeInfo> consumer) {
        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public ChangeInfo apply(Void aVoid) {
                try {
                    return gerritClient.changes().id(changeNr).get();
                } catch (RestApiException e) {
                    notifyError(e, "Failed to get Gerrit change.", project);
                    return new ChangeInfo();
                }
            }
        };
        accessGerrit(function, consumer, project);
    }

    /**
     * Support starting from Gerrit 2.7.
     */
    public void getComments(final String changeId,
                            final String revision,
                            final Project project,
                            final Consumer<Map<String, List<ReviewInput.Comment>>> consumer) {

        Function<Void, Object> function = new Function<Void, Object>() {
            @Override
            public Map<String, List<ReviewInput.Comment>> apply(Void aVoid) {
                try {
                    return gerritClient.changes().id(changeId).revision(revision).getComments();
                } catch (RestApiException e) {
                    // remove check once we drop Gerrit < 2.7 support and fail in any case
                    if (!(e instanceof HttpStatusException) || ((HttpStatusException) e).getStatusCode() != 404) {
                        notifyError(e, "Failed to get Gerrit comments.", project);
                    }
                    return new TreeMap<String, List<ReviewInput.Comment>>();
                }
            }
        };
        accessGerrit(function, consumer, project);
    }

    private boolean testConnection(GerritAuthData gerritAuthData) throws RestApiException {
        // we need to test with a temporary client with probably new (unsaved) credentials
        GerritApi tempClient = createClientWithCustomAuthData(gerritAuthData);
        if (gerritAuthData.isLoginAndPasswordAvailable()) {
            AccountInfo user = tempClient.accounts().self().get();
            return user != null;
        } else {
            tempClient.changes().list();
            return true;
        }
    }

    /**
     * Checks if user has set up correct user credentials for access in the settings.
     *
     * @return true if we could successfully login with these credentials, false if authentication failed or in the case of some other error.
     */
    public boolean checkCredentials(final Project project) {
        try {
            gerritSettings.preloadPassword();
            return checkCredentials(project, gerritSettings);
        } catch (Exception e) {
            // this method is a quick-check if we've got valid user setup.
            // if an exception happens, we'll show the reason in the login dialog that will be shown right after checkCredentials failure.
            log.info(e);
            return false;
        }
    }

    public boolean checkCredentials(Project project, final GerritAuthData gerritAuthData) {
        if (Strings.isNullOrEmpty(gerritAuthData.getHost())) {
            return false;
        }
        Boolean result = accessToGerritWithModalProgress(project, new ThrowableComputable<Boolean, Exception>() {
            @Override
            public Boolean compute() throws Exception {
                ProgressManager.getInstance().getProgressIndicator().setText("Trying to login to Gerrit");
                return testConnection(gerritAuthData);
            }
        }, gerritAuthData);
        return result == null ? false : result;
    }

    /**
     * Shows Gerrit login settings if credentials are wrong or empty and return the list of all projects
     */
    public List<ProjectInfo> getAvailableProjects(final Project project) {
        while (!checkCredentials(project)) {
            final LoginDialog dialog = new LoginDialog(project, gerritSettings, this, log);
            dialog.show();
            if (!dialog.isOK()) {
                return null;
            }
        }
        // Otherwise our credentials are valid and they are successfully stored in settings
        gerritSettings.preloadPassword();
        return accessToGerritWithModalProgress(project, new ThrowableComputable<List<ProjectInfo>, Exception>() {
            @Override
            public List<ProjectInfo> compute() throws Exception {
                ProgressManager.getInstance().getProgressIndicator().setText("Extracting info about available repositories");
                return gerritClient.projects().list();
            }
        });
    }

    public String getRef(ChangeInfo changeDetails) {
        String ref = null;
        final Map<String, RevisionInfo> revisions = changeDetails.revisions;
        for (RevisionInfo revisionInfo : revisions.values()) {
            final Map<String, FetchInfo> fetch = revisionInfo.fetch;
            for (FetchInfo fetchInfo : fetch.values()) {
                ref = fetchInfo.ref;
            }
        }
        return ref;
    }

    @SuppressWarnings("UnresolvedPropertyKey")
    public boolean testGitExecutable(final Project project) {
        final GitVcsApplicationSettings settings = GitVcsApplicationSettings.getInstance();
        final String executable = settings.getPathToGit();
        final GitVersion version;
        try {
            version = GitVersion.identifyVersion(executable);
        } catch (Exception e) {
            Messages.showErrorDialog(project, e.getMessage(), GitBundle.getString("find.git.error.title"));
            return false;
        }

        if (!version.isSupported()) {
            Messages.showWarningDialog(project, GitBundle.message("find.git.unsupported.message", version.toString(), GitVersion.MIN),
                    GitBundle.getString("find.git.success.title"));
            return false;
        }
        return true;
    }

    public String getErrorTextFromException(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = "(No exception message available)";
            log.error(message, e);
        }
        return message;
    }

    private String appendToUrlQuery(String requestUrl, String queryString) {
        if (!Strings.isNullOrEmpty(requestUrl)) {
            requestUrl += "&";
        }
        requestUrl += queryString;
        return requestUrl;
    }

    private void accessGerrit(final Function<Void, Object> function, final Consumer consumer, final Project project) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                gerritSettings.preloadPassword();
                (new Task.Backgroundable(project, "Accessing Gerrit", true) {
                    public void run(@NotNull ProgressIndicator indicator) {
                        final Object result = function.apply(null);
                        ApplicationManager.getApplication().invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                //noinspection unchecked
                                consumer.consume(result);
                            }
                        });
                    }
                }).queue();
            }
        });
    }

    private void notifyError(Exception exception, String errorMessage, Project project) {
        NotificationBuilder notification = new NotificationBuilder(project, errorMessage, getErrorTextFromException(exception));
        notificationService.notifyError(notification);
    }

    private GerritApi createClientWithCustomAuthData(GerritAuthData gerritAuthData) {
        return gerritRestClientFactory.create(gerritAuthData, sslSupport, proxyHttpClientBuilderExtension);
    }
}
