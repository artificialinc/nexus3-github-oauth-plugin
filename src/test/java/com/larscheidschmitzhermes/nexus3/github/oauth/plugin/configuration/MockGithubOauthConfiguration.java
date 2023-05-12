package com.larscheidschmitzhermes.nexus3.github.oauth.plugin.configuration;


import java.time.Duration;

public class MockGithubOauthConfiguration extends GithubOauthConfiguration {
    private Duration principalCacheTtl;

    private String org = "TEST-ORG";
    private boolean orgCheckUseRepos = false;
    private String baseRole = "";

    public MockGithubOauthConfiguration(Duration principalCacheTtl) {
        this.principalCacheTtl = principalCacheTtl;
    }

    @Override
    public String getGithubApiUrl() {
        return "http://github.example.com/api/v3";
    }

    @Override
    public Duration getPrincipalCacheTtl() {
        return principalCacheTtl;
    }

    @Override
    public String getGithubOrg() {
        return org;
    }

    public void setGithubOrg(String org) {
        this.org = org;
    }

    @Override
    public String getBaseRole() {
        return baseRole;
    }

    public void setBaseRole(String baseRole) {
        this.baseRole = baseRole;
    }

    @Override
    public boolean getOrgCheckUseRepos() {
        return orgCheckUseRepos;
    }

    public void setOrgCheckUseRepos(boolean orgCheckUseRepos) {
        this.orgCheckUseRepos = orgCheckUseRepos;
    }
}
