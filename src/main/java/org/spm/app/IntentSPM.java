package org.spm.app;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class IntentSPM {

    private String hostMac;
    private String switchID;
    private String ingressPort;
    private String appId;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getHostMac() {
        return hostMac;
    }

    public void setHostMac(String hostMac) {
        this.hostMac = hostMac;
    }

    public String getSwitchID() {
        return switchID;
    }

    public void setSwitchID(String switchID) {
        this.switchID = switchID;
    }

    public String getIngressPort() {
        return ingressPort;
    }

    public void setIngressPort(String ingressPort) {
        this.ingressPort = ingressPort;
    }
}
