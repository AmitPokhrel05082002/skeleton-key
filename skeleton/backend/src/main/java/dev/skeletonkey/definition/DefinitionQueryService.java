package dev.skeletonkey.definition;

import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.DefinitionVersionInfo;
import dev.skeletonkey.web.NotFound;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only listings of stored definitions: app codes and the versions of one app. Never loads documents. */
@Service
public class DefinitionQueryService {
    private static final Pattern APP_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final DefinitionRepository repository;

    public DefinitionQueryService(DefinitionRepository repository) {
        this.repository = repository;
    }

    /** Every app code that has at least one version, ordered by code point. */
    @Transactional(readOnly = true)
    public List<String> appCodes() {
        return repository.findAppCodes();
    }

    /**
     * Every version of the app, highest first. 404 with the same message for a code that is malformed and for a
     * code that has no versions, so the response does not reveal which case applied.
     */
    @Transactional(readOnly = true)
    public List<DefinitionVersionRef> versions(String appCode) {
        if (appCode == null || !APP_CODE.matcher(appCode).matches()) {
            throw new NotFound("App not found");
        }
        List<DefinitionVersionInfo> infos = repository.findInfoByApp(appCode);
        if (infos.isEmpty()) {
            throw new NotFound("App not found");
        }
        return infos.stream().map(DefinitionVersionRef::of).toList();
    }
}
