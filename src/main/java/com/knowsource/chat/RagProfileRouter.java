package com.knowsource.chat;

import org.springframework.stereotype.Component;

@Component
class RagProfileRouter {

    RagProfile route(ChatRequest request) {
        RagProfile requestedProfile = RagProfile.fromRequest(request.profile());
        if (requestedProfile == RagProfile.NAIVE) {
            return RagProfile.NAIVE;
        }

        return RagProfile.NAIVE;
    }
}
