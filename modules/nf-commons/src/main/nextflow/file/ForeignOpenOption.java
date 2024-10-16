package nextflow.file;

import java.nio.file.OpenOption;

/**
 * File open options for remote (foreign) file systems
 */
public enum ForeignOpenOption implements OpenOption {
    /**
     * This option causes an exception to be thrown if the file {@link java.io.InputStream}
     * is closed before all the data has been read.
     */
    FULL_DOWNLOAD
}
