package com.builtbyjuls.arat.groups.application;

import com.builtbyjuls.arat.groups.api.GroupDetailRepresentation;
import com.builtbyjuls.arat.groups.api.GroupIndexException;
import com.builtbyjuls.arat.groups.api.GroupPageRepresentation;
import com.builtbyjuls.arat.groups.api.GroupSummaryRepresentation;
import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
import com.builtbyjuls.arat.identity.api.AccountDirectory;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class GroupQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime POSTGRES_MINIMUM_TIMESTAMP = OffsetDateTime.of(-4712, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime POSTGRES_MAXIMUM_TIMESTAMP = OffsetDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000, ZoneOffset.UTC);

    private final GroupRepository groupRepository;
    private final AccountDirectory accountDirectory;
    private final ObjectMapper objectMapper;

    public GroupQueryService(GroupRepository groupRepository, AccountDirectory accountDirectory, ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.accountDirectory = accountDirectory;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<GroupDetailRepresentation> findPrivate(UUID groupId, UUID accountId) {
        return groupRepository.findPrivateDetails(groupId, accountId)
                .map(details -> GroupDetailRepresentation.from(
                        details,
                        accountDirectory.findByIds(details.activeMembers().stream()
                                .map(member -> member.accountId())
                                .toList())));
    }

    @Transactional(readOnly = true)
    public GroupPageRepresentation listForActor(UUID actorId, String cursor, Integer requestedLimit) {
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor, actorId);
        var groups = decoded == null
                ? groupRepository.listActiveMembershipPage(actorId, limit + 1)
                : groupRepository.listActiveMembershipPage(actorId, decoded.toCreatedAt(), decoded.groupId(), limit + 1);
        var hasNextPage = groups.size() > limit;
        var items = groups.stream().limit(limit).map(GroupSummaryRepresentation::from).toList();
        var nextCursor = hasNextPage
                ? encode(actorId, groups.get(limit - 1).group().createdAt(), groups.get(limit - 1).group().groupId())
                : null;
        return new GroupPageRepresentation(items, nextCursor);
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new GroupIndexException();
        }
        return requestedLimit;
    }

    private String encode(UUID actorId, OffsetDateTime createdAt, UUID groupId) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                    new CursorPayload(1, actorId, createdAt.withOffsetSameInstant(ZoneOffset.UTC).toString(), groupId)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("group cursor cannot be encoded", exception);
        }
    }

    private CursorPayload decode(String cursor, UUID actorId) {
        try {
            if (cursor.isBlank() || cursor.contains("=") || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            var payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII)), CursorPayload.class);
            if (payload == null
                    || payload.version() != 1
                    || !actorId.equals(payload.actorId())
                    || payload.createdAt() == null
                    || payload.groupId() == null) {
                throw new IllegalArgumentException();
            }
            var createdAt = OffsetDateTime.parse(payload.createdAt());
            var utcCreatedAt = createdAt.withOffsetSameInstant(ZoneOffset.UTC);
            if (createdAt.getNano() % 1_000 != 0
                    || utcCreatedAt.isBefore(POSTGRES_MINIMUM_TIMESTAMP)
                    || utcCreatedAt.isAfter(POSTGRES_MAXIMUM_TIMESTAMP)) {
                throw new IllegalArgumentException();
            }
            return new CursorPayload(payload.version(), payload.actorId(), utcCreatedAt.toString(), payload.groupId());
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw new GroupIndexException();
        }
    }

    private record CursorPayload(int version, UUID actorId, String createdAt, UUID groupId) {
        OffsetDateTime toCreatedAt() {
            return OffsetDateTime.parse(createdAt).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }
}
