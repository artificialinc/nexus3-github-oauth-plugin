package com.larscheidschmitzhermes.nexus3.github.oauth.plugin.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubRepo {
    private String name;
    private GithubUser owner;

    public GithubUser getOwner() {
        return owner;
    }

    public void setOwner(GithubUser owner) {
        this.owner = owner;
    }

    /**
     *
     * @return the real world name of the github repo, null if not specified in github
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
