package com.matchbook.model;

public enum TimeInForce {
    GTC {
        @Override
        public boolean restsRemainder() {
            return true;
        }
    },
    IOC {
        @Override
        public boolean restsRemainder() {
            return false;
        }
    },
    FOK {
        @Override
        public boolean restsRemainder() {
            return false;
        }

        @Override
        public boolean requiresFullFill() {
            return true;
        }
    };

    public abstract boolean restsRemainder();

    public boolean requiresFullFill() {
        return false;
    }
}
