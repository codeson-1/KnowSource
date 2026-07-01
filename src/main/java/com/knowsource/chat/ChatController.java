package com.knowsource.chat;

import java.util.List;

import com.knowsource.security.CurrentUserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/kbs/{kbId}/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final CurrentUserService currentUserService;

    public ChatController(
            ChatService chatService,
            ChatSessionService chatSessionService,
            CurrentUserService currentUserService) {
        this.chatService = chatService;
        this.chatSessionService = chatSessionService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ChatResponse answer(@PathVariable String kbId, @RequestBody ChatRequest request) {
        return chatService.answer(kbId, request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String kbId, @RequestBody ChatRequest request) {
        return chatService.stream(kbId, request);
    }

    @GetMapping("/sessions")
    public List<ChatSessionSummaryResponse> listSessions(
            @PathVariable String kbId,
            @RequestParam(required = false) Integer limit) {
        return chatSessionService.listSessions(kbId, currentUserService.currentUserId(), limit);
    }

    @GetMapping("/sessions/{sessionId}")
    public ChatSessionDetailResponse getSession(@PathVariable String kbId, @PathVariable String sessionId) {
        return chatSessionService.getSession(kbId, currentUserService.currentUserId(), sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable String kbId, @PathVariable String sessionId) {
        chatSessionService.deleteSession(kbId, currentUserService.currentUserId(), sessionId);
    }
}
