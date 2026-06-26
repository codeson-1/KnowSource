package com.knowsource.kb;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kbs")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBaseResponse response = knowledgeBaseService.create(request);
        return ResponseEntity.created(URI.create("/api/kbs/" + response.id())).body(response);
    }

    @GetMapping
    public List<KnowledgeBaseResponse> listMine() {
        return knowledgeBaseService.listMine();
    }

    @GetMapping("/{kbId}")
    public KnowledgeBaseResponse get(@PathVariable String kbId) {
        return knowledgeBaseService.get(kbId);
    }

    @PutMapping("/{kbId}")
    public KnowledgeBaseResponse update(
            @PathVariable String kbId,
            @RequestBody UpdateKnowledgeBaseRequest request) {
        return knowledgeBaseService.update(kbId, request);
    }

    @DeleteMapping("/{kbId}")
    public ResponseEntity<Void> delete(@PathVariable String kbId) {
        knowledgeBaseService.delete(kbId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{kbId}/members")
    public List<KnowledgeBaseMemberResponse> listMembers(@PathVariable String kbId) {
        return knowledgeBaseService.listMembers(kbId);
    }

    @PostMapping("/{kbId}/members")
    public ResponseEntity<KnowledgeBaseMemberResponse> addMember(
            @PathVariable String kbId,
            @RequestBody MemberRequest request) {
        KnowledgeBaseMemberResponse response = knowledgeBaseService.addMember(kbId, request);
        return ResponseEntity.created(URI.create("/api/kbs/" + kbId + "/members/" + response.userId())).body(response);
    }

    @PutMapping("/{kbId}/members/{userId}")
    public KnowledgeBaseMemberResponse updateMember(
            @PathVariable String kbId,
            @PathVariable long userId,
            @RequestBody MemberRequest request) {
        return knowledgeBaseService.updateMember(kbId, userId, request);
    }

    @DeleteMapping("/{kbId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable String kbId,
            @PathVariable long userId) {
        knowledgeBaseService.removeMember(kbId, userId);
        return ResponseEntity.noContent().build();
    }
}
