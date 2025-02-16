package com.ctrip.framework.drc.console.service;

import com.ctrip.xpipe.api.endpoint.Endpoint;

import java.util.Map;

public interface MySqlService {
    
    // forward by mha
    Map<String, String> getCreateTableStatements(String mha, String unionFilter, Endpoint endpoint);
    
    // forward by mha
    Integer getAutoIncrement(String mha,String sql,int index,Endpoint endpoint);
    
    // forward by mha
    String getRealExecutedGtid(String mha);
}
