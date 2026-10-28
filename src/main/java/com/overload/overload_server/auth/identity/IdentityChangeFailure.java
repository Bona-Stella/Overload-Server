package com.overload.overload_server.auth.identity;

public class IdentityChangeFailure extends RuntimeException {
    public final int status;
    public final String code;
    public IdentityChangeFailure(int status,String code){super(code);this.status=status;this.code=code;}
}
