package storage;

import exception.StorageException;

/**
 * Where data is kept. The engine depends on this interface only, so it never knows about CSV or
 * files, and tests can use an in-memory version.
 */
public interface Storage {

    void save(StorageData data) throws StorageException;

    /** Returns empty data (not an error) when nothing has been saved yet. */
    StorageData load() throws StorageException;
}
