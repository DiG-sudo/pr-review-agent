package com.guodi.pragent.harness.restore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guodi.pragent.reviewer.github.GitHubReviewLookup;
import com.guodi.pragent.tables.reviewrun.ReviewRunEntity;
import com.guodi.pragent.tables.reviewrun.ReviewRunMapper;
import com.guodi.pragent.tables.toolround.ToolRoundEntity;
import com.guodi.pragent.tables.toolround.ToolRoundMapper;

/** Handles the publication and unfinished-round decisions for an existing RUNNING run. */
@Component
public class RequestRestore {
    private final ReviewRunMapper reviewRunMapper;
    private final ToolRoundMapper toolRoundMapper;
    private final GitHubReviewLookup gitHubReviewLookup;
    private final TransactionTemplate transactions;

    public RequestRestore(ReviewRunMapper reviewRunMapper, ToolRoundMapper toolRoundMapper,
            GitHubReviewLookup gitHubReviewLookup, TransactionTemplate transactions) {
        this.reviewRunMapper = reviewRunMapper;
        this.toolRoundMapper = toolRoundMapper;
        this.gitHubReviewLookup = gitHubReviewLookup;
        this.transactions = transactions;
    }

    public RestoreResult restore(ReviewRunEntity run) {
        Objects.requireNonNull(run, "run");
        if (run.getPublicationKey() == null || run.getPublicationKey().isBlank()) {
            throw new IllegalStateException("review run has no publication key: " + run.getId());
        }

        OptionalLong remoteReview;
        try {
            remoteReview = gitHubReviewLookup.findPublished(run.getRepository(),
                    run.getPullRequestNumber(), run.getHeadSha(), run.getPublicationKey());
        } catch (IOException error) {
            return new RestoreResult(RestoreResult.Status.PENDING_VERIFICATION,
                    0, null);
        }

        List<ToolRoundEntity> rounds = toolRoundMapper.selectList(
                Wrappers.<ToolRoundEntity>lambdaQuery()
                        .eq(ToolRoundEntity::getRunId, run.getId())
                        .orderByAsc(ToolRoundEntity::getRoundNumber));
        ToolRoundEntity last = rounds.isEmpty() ? null : rounds.getLast();

        if (remoteReview.isPresent()) {
            String reviewId = Long.toString(remoteReview.getAsLong());
            transactions.executeWithoutResult(status -> {
                abandonIfOpen(run.getId(), last);
                ReviewRunEntity update = new ReviewRunEntity();
                update.setStatus("PUBLISHED");
                update.setExternalReviewId(reviewId);
                int changed = reviewRunMapper.update(update,
                        Wrappers.<ReviewRunEntity>lambdaUpdate()
                                .eq(ReviewRunEntity::getId, run.getId())
                                .eq(ReviewRunEntity::getStatus, "RUNNING"));
                if (changed != 1) {
                    throw new IllegalStateException("review run is no longer RUNNING: " + run.getId());
                }
            });
            return new RestoreResult(RestoreResult.Status.PUBLISHED,
                    0, reviewId);
        }

        if (last != null && "OPEN".equals(last.getStatus())) {
            transactions.executeWithoutResult(status -> abandonIfOpen(run.getId(), last));
        }

        int nextRoundNumber = last == null ? 1 : last.getRoundNumber() + 1;
        return new RestoreResult(RestoreResult.Status.RESUME, nextRoundNumber, null);
    }

    private void abandonIfOpen(Long runId, ToolRoundEntity round) {
        if (round == null || !"OPEN".equals(round.getStatus())) {
            return;
        }
        ToolRoundEntity update = new ToolRoundEntity();
        update.setStatus("ABANDONED");
        int changed = toolRoundMapper.update(update,
                Wrappers.<ToolRoundEntity>lambdaUpdate()
                        .eq(ToolRoundEntity::getId, round.getId())
                        .eq(ToolRoundEntity::getRunId, runId)
                        .eq(ToolRoundEntity::getStatus, "OPEN"));
        if (changed != 1) {
            throw new IllegalStateException("tool round is no longer OPEN: " + round.getId());
        }
    }
}
