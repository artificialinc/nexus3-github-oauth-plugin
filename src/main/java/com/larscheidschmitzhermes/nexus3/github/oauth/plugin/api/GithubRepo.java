package com.larscheidschmitzhermes.nexus3.github.oauth.plugin.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubRepo {
    private String name;

    // Use org for owner because org only has login name. User object has teams and other stuff which fails serialization
    private GithubOrg owner;

    public GithubOrg getOwner() {
        return owner;
    }

    public void setOwner(GithubOrg owner) {
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
