package com.sharedsync.shared.contoller;

import com.sharedsync.shared.presence.core.SharedPresenceFacade;
import com.sharedsync.shared.presence.dto.PresenceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final SharedPresenceFacade presenceFacade;

    @GetMapping("/{roomId}")
    public List<PresenceSnapshot> getPresence(@PathVariable("roomId") String roomId) {
        return presenceFacade.getPresence(roomId);
    }
}
