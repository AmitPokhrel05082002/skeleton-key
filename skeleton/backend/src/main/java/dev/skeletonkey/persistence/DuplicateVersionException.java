package dev.skeletonkey.persistence;

/** The (app code, version) pair already exists: another writer took the version number first. */
public class DuplicateVersionException extends RuntimeException {
    public DuplicateVersionException(String appCode, int version) {
        super("Definition version " + appCode + " v" + version + " already exists");
    }
}
