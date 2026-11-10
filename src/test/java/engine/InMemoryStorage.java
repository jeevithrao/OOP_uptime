package engine;

import exception.StorageException;
import storage.Storage;
import storage.StorageData;

/** Test double for Storage: keeps data in memory, counts saves, and can be told to fail. */
class InMemoryStorage implements Storage {

    StorageData saved = StorageData.empty();
    int saveCount = 0;
    boolean failOnSave = false;
    boolean failOnLoad = false;

    @Override
    public void save(StorageData data) throws StorageException {
        if (failOnSave) throw new StorageException("disk full");
        saved = data;
        saveCount++;
    }

    @Override
    public StorageData load() throws StorageException {
        if (failOnLoad) throw new StorageException("corrupt");
        return saved;
    }
}
