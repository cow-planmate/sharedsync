package com.sharedsync.shared.controller;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Payload;

import com.sharedsync.shared.dto.WRequest;
import com.sharedsync.shared.dto.WResponse;
import com.sharedsync.shared.service.SharedService;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class SharedController <req extends WRequest, res extends WResponse, T extends SharedService<req, res>> {
    protected final T service;

    protected res handleCreate(@DestinationVariable int rootEntityId, @Payload req request) {
        request.setRootId(String.valueOf(rootEntityId));
        res response = service.create(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected res handleRead(@DestinationVariable int rootEntityId, @Payload req request) {
        request.setRootId(String.valueOf(rootEntityId));
        res response = service.read(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected res handleUpdate(@DestinationVariable int rootEntityId, @Payload req request) {
        request.setRootId(String.valueOf(rootEntityId));
        res response = service.update(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected res handleDelete(@DestinationVariable int rootEntityId, @Payload req request) {
        request.setRootId(String.valueOf(rootEntityId));
        res response = service.delete(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected Object handleUndo(@DestinationVariable int rootEntityId) {
        return service.undo(String.valueOf(rootEntityId));
    }

    protected Object handleRedo(@DestinationVariable int rootEntityId) {
        return service.redo(String.valueOf(rootEntityId));
    }

}
