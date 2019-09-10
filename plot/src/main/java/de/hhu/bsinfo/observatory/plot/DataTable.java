package de.hhu.bsinfo.observatory.plot;

public class DataTable extends de.erichseifert.gral.data.DataTable {

    @SafeVarargs
    DataTable(Class<? extends Comparable<?>>... types) {
        super(types);
    }

    public DataTable(int cols, java.lang.Class<? extends java.lang.Comparable<?>> type) {
        super(cols, type);
    }

    @Override
    public String toString() {
        return getName();
    }
}
