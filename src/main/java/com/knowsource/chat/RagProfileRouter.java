package com.knowsource.chat;

import org.springframework.stereotype.Component;

@Component
class RagProfileRouter {

    RagProfile route(ChatRequest request, boolean hasHistory) {
        RagProfile requestedProfile = RagProfile.fromRequest(request.profile());
        if (requestedProfile != RagProfile.AUTO) {
            return requestedProfile;
        }
        if (hasHistory) {
            return RagProfile.MODULAR;
        }

        return RagProfile.NAIVE;
    }
}
