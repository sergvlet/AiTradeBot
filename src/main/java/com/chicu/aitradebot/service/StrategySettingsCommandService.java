package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.events.StrategySettingsUpdatedEvent;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySettingsCommandService {

    private final StrategySettingsRepository strategySettingsRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;

    /**
     * Сохраняет patch в отдельной транзакции.
     * При optimistic lock делает несколько повторов.
     */
    public StrategySettings savePatchWithRetry(Long chatId,
                                               StrategyType type,
                                               StrategySettings patch) {

        if (chatId == null) {
            throw new IllegalArgumentException("chatId == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type == null");
        }

        ObjectOptimisticLockingFailureException lastLockEx = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return savePatchInNewTransaction(chatId, type, patch);
            } catch (ObjectOptimisticLockingFailureException ex) {
                lastLockEx = ex;

                log.warn("⚠️ Optimistic lock при сохранении StrategySettings chatId={} type={} attempt={}/3: {}",
                        chatId, type, attempt, ex.getMessage());

                sleepQuietly(40L * attempt);
            }
        }

        throw lastLockEx;
    }

    /**
     * Чтение уже существующих настроек без создания новых.
     */
    public Optional<StrategySettings> findExisting(Long chatId, StrategyType type) {
        if (chatId == null || type == null) {
            return Optional.empty();
        }
        return strategySettingsRepository.findByChatIdAndType(chatId, type);
    }

    private StrategySettings savePatchInNewTransaction(Long chatId,
                                                       StrategyType type,
                                                       StrategySettings patch) {

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setName("strategy-settings-save-" + type.name());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return Objects.requireNonNull(tx.execute(status -> {
            StrategySettings entity = strategySettingsRepository
                    .findByChatIdAndType(chatId, type)
                    .orElseGet(() -> {
                        StrategySettings created = new StrategySettings();
                        created.setChatId(chatId);
                        created.setType(type);
                        return created;
                    });

            boolean isNew = entity.getId() == null;
            boolean changed = applyPatch(chatId, type, patch, entity);

            if (!isNew && !changed) {
                log.debug("↔ StrategySettings unchanged chatId={} type={} — save skipped",
                        chatId, type);
                return entity;
            }

            StrategySettings saved = strategySettingsRepository.saveAndFlush(entity);

            registerAfterCommit(saved);

            log.info("✅ StrategySettings saved chatId={} type={} id={} ex={} net={} symbol={} tf={}",
                    saved.getChatId(),
                    saved.getType(),
                    saved.getId(),
                    saved.getExchangeName(),
                    saved.getNetworkType(),
                    saved.getSymbol(),
                    saved.getTimeframe());

            return saved;
        }));
    }

    private boolean applyPatch(Long chatId,
                               StrategyType type,
                               StrategySettings patch,
                               StrategySettings target) {

        boolean changed = false;

        if (!Objects.equals(target.getChatId(), chatId)) {
            target.setChatId(chatId);
            changed = true;
        }

        if (target.getType() != type) {
            target.setType(type);
            changed = true;
        }

        if (patch == null) {
            return changed;
        }

        BeanWrapper src = new BeanWrapperImpl(patch);
        BeanWrapper dst = new BeanWrapperImpl(target);

        Set<String> blocked = new HashSet<>();
        blocked.add("class");
        blocked.add("id");
        blocked.add("version");
        blocked.add("chatId");
        blocked.add("type");
        blocked.add("mlConfidence");
        blocked.add("createdAt");
        blocked.add("updatedAt");

        for (PropertyDescriptor pd : src.getPropertyDescriptors()) {
            String name = pd.getName();

            if (blocked.contains(name)) {
                continue;
            }
            if (!src.isReadableProperty(name) || !dst.isWritableProperty(name)) {
                continue;
            }

            Object value = src.getPropertyValue(name);
            if (value == null) {
                continue;
            }

            Object current = dst.getPropertyValue(name);
            if (sameValue(current, value)) {
                continue;
            }

            dst.setPropertyValue(name, value);
            changed = true;
        }

        return changed;
    }

    private boolean sameValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a instanceof BigDecimal bd1 && b instanceof BigDecimal bd2) {
            return bd1.compareTo(bd2) == 0;
        }

        return Objects.equals(a, b);
    }

    private void registerAfterCommit(StrategySettings saved) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishUpdated(saved);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishUpdated(saved);
            }
        });
    }

    private void publishUpdated(StrategySettings saved) {
        try {
            eventPublisher.publishEvent(
                    new StrategySettingsUpdatedEvent(saved.getChatId(), saved.getType())
            );
        } catch (Exception e) {
            log.warn("⚠️ Не удалось опубликовать StrategySettingsUpdatedEvent chatId={} type={} err={}",
                    saved.getChatId(), saved.getType(), e.toString());
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}