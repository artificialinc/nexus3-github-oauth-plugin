package com.larscheidschmitzhermes.nexus3.github.oauth.plugin.api;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Collections;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.GithubAuthenticationException;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.GithubPrincipal;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.configuration.GithubOauthConfiguration;

@Singleton
@Named("GithubApiClient")
public class GithubApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GithubApiClient.class);

    private HttpClient client;
    private GithubOauthConfiguration configuration;
    private ObjectMapper mapper;
    // Cache token lookups to reduce the load on Github's User API to prevent hitting the rate limit.
    private Cache<String, GithubPrincipal> tokenToPrincipalCache;

    public GithubApiClient() {
        init();
    }

    public GithubApiClient(HttpClient client, GithubOauthConfiguration configuration) {
        this.client = client;
        this.configuration = configuration;
        mapper = new ObjectMapper();
        initPrincipalCache();
    }

    @Inject
    public GithubApiClient(GithubOauthConfiguration configuration) {
        this.configuration = configuration;
        init();
    }

    public void init() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(configuration.getRequestConnectTimeout())
                .setConnectionRequestTimeout(configuration.getRequestConnectionRequestTimeout())
                .setSocketTimeout(configuration.getRequestSocketTimeout())
                .build();
        client = HttpClientBuilder
                .create()
                .setDefaultRequestConfig(config)
                .build();
        mapper = new ObjectMapper();
        initPrincipalCache();
    }

    private void initPrincipalCache() {
        tokenToPrincipalCache = CacheBuilder.newBuilder()
                .expireAfterWrite(configuration.getPrincipalCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    public GithubPrincipal authz(String login, char[] token) throws GithubAuthenticationException {
        // Combine the login and the token as the cache key since they are both used to generate the principal. If either changes we should obtain a new
        // principal.
        String cacheKey = login + "|" + new String(token);
        GithubPrincipal cached = tokenToPrincipalCache.getIfPresent(cacheKey);
        if (cached != null) {
            LOGGER.debug("Using cached principal for login: {}", login);
            return cached;
        } else {
            GithubPrincipal principal = doAuthz(login, token);
            tokenToPrincipalCache.put(cacheKey, principal);
            return principal;
        }
    }

    private GithubPrincipal doAuthz(String loginName, char[] token) throws GithubAuthenticationException {
        GithubUser githubUser = retrieveGithubUser(loginName, token);
        GithubPrincipal principal = new GithubPrincipal();

        principal.setUsername(githubUser.getLogin());

        Set<String> orgs = Collections.emptySet();

        if (configuration.getOrgCheckUseRepos()) {
            LOGGER.info("orgCheckUseRepos is enabled. Checking for org membership using repo access");
            if (configuration.getGithubOrg() != null && !configuration.getGithubOrg().equals("")) {
                orgs = Arrays.stream(configuration.getGithubOrg().split(","))
                        .collect(Collectors.toSet());
            }
            if (orgs.isEmpty()) {
                LOGGER.warn("orgCheckUseRepos is enabled but no orgs are configured. This could allow unauthenticated users in");
            }
        }

        Set<String> roles = generateRolesFromGithubRepos(token, loginName, orgs);

        roles.addAll(generateRolesFromGithubOrgMemberships(token, loginName));

        principal.setRoles(roles);
        principal.setOauthToken(token);

        return principal;
    }


    private GithubUser retrieveGithubUser(String loginName, char[] token) throws GithubAuthenticationException {
        GithubUser githubUser = getAndSerializeObject(configuration.getGithubUserUri(), token,GithubUser.class);

        if (!loginName.equals(githubUser.getLogin())) {
            throw new GithubAuthenticationException("Given username does not match Github Username!");
        }

        if (configuration.getGithubOrg() != null && !configuration.getGithubOrg().equals("") && !configuration.getOrgCheckUseRepos()) {
            checkUserInOrg(configuration.getGithubOrg(), token);
        }
        return githubUser;
    }

    private void checkUserInOrg(String githubOrg, char[] token) throws GithubAuthenticationException {
        Set<GithubOrg> orgsInToken = getAndSerializeCollection(configuration.getGithubUserOrgsUri(), token, GithubOrg.class);
        String[] allowedOrgs = githubOrg.split(",");

        if (orgsInToken.stream().noneMatch(org -> Arrays.asList(allowedOrgs).contains(org.getLogin()))) {
            throw new GithubAuthenticationException("Given username is not in the Github Organization '" + githubOrg + "' or the Organization is not in the allowed list!");
        }
    }

    private Set<String> generateRolesFromGithubRepos(char[] token, String loginName, Set<String> orgs) throws GithubAuthenticationException {
        int page = 1;
        Set<GithubRepo> repos = getAndSerializeCollectionPage(configuration.getGithubUserReposUri(), token, GithubRepo.class, page);
        if (repos.size() >= 100) {
            while(true) {
                page++;
                Set<GithubRepo> reposPage = getAndSerializeCollectionPage(configuration.getGithubUserReposUri(), token, GithubRepo.class, page);
                repos.addAll(reposPage);
                if (reposPage.size() < 100) {
                    break;
                }
            }
        }
        // TODO: Do this in one pass? Or do a bigger refactor. Putting this here for now because owner/repo are still split
        if (orgs.size() > 0) {
            Set<GithubRepo> reposInAllowedOrgs = repos.stream().filter(repo -> {
                LOGGER.info("Checking repo {} with owner {} in orgs {}", repo.getName(), repo.getOwner().getLogin(), orgs);
                return orgs.contains(repo.getOwner().getLogin());
            }).collect(Collectors.toSet());
            if (reposInAllowedOrgs.size() == 0) {
                throw new GithubAuthenticationException("Given username has no repo access in '" + orgs + "'!");
            }
        }
        return repos.stream().map(this::mapGithubRepoToNexusRole).collect(Collectors.toSet());
    }

    private Set<String> generateRolesFromGithubOrgMemberships(char[] token, String loginName) throws GithubAuthenticationException {
        Set<GithubTeam> teams = getAndSerializeCollection(configuration.getGithubUserTeamsUri(), token, GithubTeam.class);
        if (teams.size() >= 100) {
            LOGGER.warn("Fetching only the first 100 teams for user '{}'", loginName);
        }
        return teams.stream().map(this::mapGithubTeamToNexusRole).collect(Collectors.toSet());
    }

    private String mapGithubTeamToNexusRole(GithubTeam team) {
        return team.getOrganization().getLogin() + "/" + team.getName();
    }

    private String mapGithubRepoToNexusRole(GithubRepo repo) {
        return repo.getOwner().getLogin() + "/" + repo.getName();
    }

    private BasicHeader constructGithubAuthorizationHeader(char[] token) {
        return new BasicHeader("Authorization", "token " + new String(token));
    }

    private <T> T getAndSerializeObject(String uri, char[] token, Class<T> clazz) throws GithubAuthenticationException {
        try (InputStreamReader reader = executeGet(uri, token)) {
            JavaType javaType = mapper.getTypeFactory()
                    .constructType(clazz);
            return mapper.readValue(reader, javaType);
        } catch (IOException e) {
            throw new GithubAuthenticationException(e);
        }
    }

    private <T> Set<T> getAndSerializeCollection(String uri, char[] token, Class<T> clazz) throws GithubAuthenticationException {
        Set<T> result;
        try (InputStreamReader reader = executeGet(uri  + "?per_page=100", token)) {
            JavaType javaType = mapper.getTypeFactory()
                    .constructCollectionType(Set.class, clazz);
            result = mapper.readValue(reader, javaType);
        } catch (IOException e) {
            throw new GithubAuthenticationException(e);
        }
        return result;
    }

    private <T> Set<T> getAndSerializeCollectionPage(String uri, char[] token, Class<T> clazz, int page) throws GithubAuthenticationException {
        Set<T> result;
        try (InputStreamReader reader = executeGetPage(uri  + "?per_page=100", token, page)) {
            JavaType javaType = mapper.getTypeFactory()
                    .constructCollectionType(Set.class, clazz);
            result = mapper.readValue(reader, javaType);
        } catch (IOException e) {
            throw new GithubAuthenticationException(e);
        }
        return result;
    }

    private InputStreamReader executeGet(String uri, char[] token) throws GithubAuthenticationException {
        HttpGet request = new HttpGet(uri);
        request.addHeader(constructGithubAuthorizationHeader(token));
        try {
            HttpResponse response = client.execute(request);
            if (response.getStatusLine().getStatusCode() != 200) {
                LOGGER.warn("Authentication failed, status code was {}",
                        response.getStatusLine().getStatusCode());
                request.releaseConnection();
                throw new GithubAuthenticationException("Authentication failed.");
            }
            return new InputStreamReader(response.getEntity().getContent());
        } catch (IOException e) {
            request.releaseConnection();
            throw new GithubAuthenticationException(e);
        }
    }

    private InputStreamReader executeGetPage(String uri, char[] token, int page) throws GithubAuthenticationException {
        HttpGet request = new HttpGet(uri + "&page=" + page);
        request.addHeader(constructGithubAuthorizationHeader(token));
        try {
            HttpResponse response = client.execute(request);
            if (response.getStatusLine().getStatusCode() != 200) {
                LOGGER.warn("Authentication failed, status code was {}",
                        response.getStatusLine().getStatusCode());
                request.releaseConnection();
                throw new GithubAuthenticationException("Authentication failed.");
            }
            return new InputStreamReader(response.getEntity().getContent());
        } catch (IOException e) {
            request.releaseConnection();
            throw new GithubAuthenticationException(e);
        }
    }

}
