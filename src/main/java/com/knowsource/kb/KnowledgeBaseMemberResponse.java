package com.knowsource.kb;

public record KnowledgeBaseMemberResponse(
        long userId,
        String username,
        String globalRole,
        String memberRole) {
}
