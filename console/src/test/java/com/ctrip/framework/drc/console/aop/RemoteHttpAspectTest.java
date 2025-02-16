package com.ctrip.framework.drc.console.aop;

import com.ctrip.framework.drc.console.config.DefaultConsoleConfig;
import com.ctrip.framework.drc.console.service.MySqlService;
import com.ctrip.framework.drc.console.service.impl.MySqlServiceImpl;
import com.ctrip.framework.drc.console.utils.DalUtils;
import com.ctrip.framework.drc.core.driver.command.netty.endpoint.MySqlEndpoint;
import com.ctrip.xpipe.api.endpoint.Endpoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;

public class RemoteHttpAspectTest {
    
    @InjectMocks
    private RemoteHttpAspect aop;
    
    @Mock
    private DefaultConsoleConfig consoleConfig;

    @Mock
    private DalUtils dalUtils;
    
    @Spy
    private MySqlServiceImpl mySqlService;
    
    private MySqlService proxy;
    

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        Mockito.when(consoleConfig.getRegion()).thenReturn("region1");
        Mockito.when(consoleConfig.getCenterRegionUrl()).thenReturn("centerRegionUrl");
        Mockito.when(consoleConfig.getRegionForDc(Mockito.anyString())).thenReturn("region1");
        Mockito.when(consoleConfig.getPublicCloudRegion()).thenReturn(
                new HashSet<>(){{
                    add("region2");
                }}
        );
        Mockito.when(consoleConfig.getConsoleRegionUrls()).thenReturn(
                new HashMap<>(){{
                  put("region1","url1");
                  put("region2","url2");
                }}
        );
        AspectJProxyFactory factory = new AspectJProxyFactory(mySqlService);
        factory.setProxyTargetClass(true);
        factory.addAspect(aop);
        proxy= factory.getProxy();
    }

    @Test
    public void testForwardByArgs() throws SQLException {
        Mockito.when(dalUtils.getDcName(Mockito.eq("mha1"),Mockito.anyInt())).thenReturn("dc2");
        
        // forward
        Mockito.when(consoleConfig.getRegionForDc(Mockito.eq("dc2"))).thenReturn("region2");
        proxy.getCreateTableStatements(
                "mha1",
                "unionFilter",
                new MySqlEndpoint("ip", 3306, "usr", "psw", true)
        );
        Mockito.verify(mySqlService,Mockito.never()).getCreateTableStatements(
                Mockito.anyString(),Mockito.anyString(),Mockito.any(Endpoint.class)
        );
        
        // not forward
        // case1:localRegion is not a public cloud region
        Mockito.when(consoleConfig.getRegionForDc(Mockito.eq("dc2"))).thenReturn("region1");
        proxy.getCreateTableStatements(
                "mha1", 
                "unionFilter",
                new MySqlEndpoint("ip", 3306, "usr", "psw", true)
        );
        Mockito.verify(mySqlService,Mockito.atLeastOnce()).getCreateTableStatements(
                Mockito.anyString(),Mockito.anyString(),Mockito.any(Endpoint.class)
        );

        // case2:localRegion is a public cloud region
        Mockito.when(consoleConfig.getRegion()).thenReturn("region2");
        proxy.getCreateTableStatements(
                "mha1",
                "unionFilter",
                new MySqlEndpoint("ip", 3306, "usr", "psw", true)
        );
        
    }
    
}