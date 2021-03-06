// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
package com.cloud.ucs.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.ucs.structure.UcsTemplate;
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.response.*;
import org.apache.log4j.Logger;

import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceService;
import com.cloud.ucs.database.UcsBladeDao;
import com.cloud.ucs.database.UcsBladeVO;
import com.cloud.ucs.database.UcsManagerDao;
import com.cloud.ucs.database.UcsManagerVO;
import com.cloud.ucs.structure.ComputeBlade;
import com.cloud.ucs.structure.UcsCookie;
import com.cloud.ucs.structure.UcsProfile;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.SearchCriteria2;
import com.cloud.utils.db.SearchCriteriaService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.xmlobject.XmlObject;
import com.cloud.utils.xmlobject.XmlObjectParser;

@Local(value = { UcsManager.class })
public class UcsManagerImpl implements UcsManager {
    public static final Logger s_logger = Logger.getLogger(UcsManagerImpl.class);
    public static final Long COOKIE_TTL = TimeUnit.MILLISECONDS.convert(100L, TimeUnit.MINUTES);
    public static final Long COOKIE_REFRESH_TTL = TimeUnit.MILLISECONDS.convert(10L, TimeUnit.MINUTES);

    @Inject
    private UcsManagerDao ucsDao;
    @Inject
    private ResourceService resourceService;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private ClusterDetailsDao clusterDetailsDao;
    @Inject
    private UcsBladeDao bladeDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private ConfigurationDao configDao;

    private final Map<Long, UcsCookie> cookies = new HashMap<Long, UcsCookie>();
    private String name;
    private int runLevel;
    private Map<String, Object> params;
    private ScheduledExecutorService syncBladesExecutor;
    private int syncBladeInterval;

    private class SyncBladesThread implements Runnable {

		private void discoverNewBlades(Map<String, UcsBladeVO> previous,
				Map<String, ComputeBlade> now, UcsManagerVO mgr) {
			for (Map.Entry<String, ComputeBlade> e : now.entrySet()) {
				String dn = e.getKey();
				if (previous.keySet().contains(dn)) {
					continue;
				}

				ComputeBlade nc = e.getValue();
				UcsBladeVO vo = new UcsBladeVO();
				vo.setDn(nc.getDn());
				vo.setUcsManagerId(mgr.getId());
				vo.setUuid(UUID.randomUUID().toString());
				bladeDao.persist(vo);
				s_logger.debug(String.format("discovered a new UCS blade[dn:%s] during sync", nc.getDn()));
			}
		}
		
		private void decommissionFadedBlade(Map<String, UcsBladeVO> previous, Map<String, ComputeBlade> now) {
			for (Map.Entry<String, UcsBladeVO> e : previous.entrySet()) {
				String dn = e.getKey();
				if (now.keySet().contains(dn)) {
					continue;
				}
				
				UcsBladeVO vo = e.getValue();
				bladeDao.remove(vo.getId());
				s_logger.debug(String.format("decommission faded blade[dn:%s] during sync", vo.getDn()));
			}
		}

    	private void syncBlades(UcsManagerVO mgr) {
    		SearchCriteriaService<UcsBladeVO, UcsBladeVO> q = SearchCriteria2.create(UcsBladeVO.class);
    		q.addAnd(q.getEntity().getUcsManagerId(), Op.EQ, mgr.getId());
    		List<UcsBladeVO> pblades = q.list();
    		if (pblades.isEmpty()) {
    			return;
    		}

    		
    		Map<String, UcsBladeVO> previousBlades = new HashMap<String, UcsBladeVO>(pblades.size());
    		for (UcsBladeVO b : pblades) {
    			previousBlades.put(b.getDn(), b);
    		}

    		List<ComputeBlade> cblades = listBlades(mgr.getId());
    		Map<String, ComputeBlade> currentBlades = new HashMap<String, ComputeBlade>(cblades.size());
    		for (ComputeBlade c : cblades) {
    			currentBlades.put(c.getDn(), c);
    		}
    		
    		discoverNewBlades(previousBlades, currentBlades, mgr);
    		decommissionFadedBlade(previousBlades, currentBlades);
    	}

		@Override
		public void run() {
			try {
				List<UcsManagerVO> mgrs = ucsDao.listAll();
				for (UcsManagerVO mgr : mgrs) {
					syncBlades(mgr);
				}
			} catch (Throwable t) {
				s_logger.warn(t.getMessage(), t);
			}
		}

    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        return true;
    }

    @Override
    public boolean start() {
        try {
            syncBladeInterval = Integer.valueOf(configDao.getValue(Config.UCSSyncBladeInterval.key()));
        } catch (NumberFormatException e) {
            syncBladeInterval = 600;
        }
    	syncBladesExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("UCS-SyncBlades"));
    	syncBladesExecutor.scheduleAtFixedRate(new SyncBladesThread(), syncBladeInterval, syncBladeInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    private void discoverBlades(UcsManagerVO ucsMgrVo) {
        List<ComputeBlade> blades = listBlades(ucsMgrVo.getId());
        for (ComputeBlade b : blades) {
            UcsBladeVO vo = new UcsBladeVO();
            vo.setDn(b.getDn());
            vo.setUcsManagerId(ucsMgrVo.getId());
            vo.setUuid(UUID.randomUUID().toString());
            if (!"".equals(b.getAssignedToDn())) {
                vo.setProfileDn(b.getAssignedToDn());
            }
            bladeDao.persist(vo);
        }
    }

    @Override
    @DB
    public UcsManagerResponse addUcsManager(AddUcsManagerCmd cmd) {
        SearchCriteriaService<UcsManagerVO, UcsManagerVO> q = SearchCriteria2.create(UcsManagerVO.class);
        q.addAnd(q.getEntity().getUrl(), Op.EQ, cmd.getUrl());
        UcsManagerVO mgrvo = q.find();
        if (mgrvo != null) {
            throw new IllegalArgumentException(String.format("duplicate UCS manager. url[%s] is used by another UCS manager already", cmd.getUrl()));
        }

        try {
            UcsManagerVO vo = new UcsManagerVO();
            vo.setUuid(UUID.randomUUID().toString());
            vo.setPassword(cmd.getPassword());
            vo.setUrl(cmd.getUrl());
            vo.setUsername(cmd.getUsername());
            vo.setZoneId(cmd.getZoneId());
            vo.setName(cmd.getName());

            Transaction txn = Transaction.currentTxn();
            txn.start();
            mgrvo = ucsDao.persist(vo);
            txn.commit();
            UcsManagerResponse rsp = new UcsManagerResponse();
            rsp.setId(String.valueOf(vo.getId()));
            rsp.setName(vo.getName());
            rsp.setUrl(vo.getUrl());
            rsp.setZoneId(String.valueOf(vo.getZoneId()));

            discoverBlades(vo);
            return rsp;
        } catch (CloudRuntimeException e) {
            if (mgrvo != null) {
                ucsDao.remove(mgrvo.getId());
            }
            throw e;
        }
    }

    private String getCookie(Long ucsMgrId) {
        try {
            UcsCookie ucsCookie = cookies.get(ucsMgrId);
            long currentTime = System.currentTimeMillis();
            UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
            UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
            String cmd = null;
            if (ucsCookie == null) {
                cmd = UcsCommands.loginCmd(mgrvo.getUsername(), mgrvo.getPassword());
            }
            else {
                String cookie = ucsCookie.getCookie();
                long cookieStartTime = ucsCookie.getStartTime();
                if(currentTime - cookieStartTime > COOKIE_TTL) {
                    cmd = UcsCommands.loginCmd(mgrvo.getUsername(), mgrvo.getPassword());
                }
                else if(currentTime - cookieStartTime > COOKIE_REFRESH_TTL) {
                    cmd = UcsCommands.refreshCmd(mgrvo.getUsername(), mgrvo.getPassword(), cookie);
                }
            }
            if(cmd != null) {
                String ret = client.call(cmd);
                XmlObject xo = XmlObjectParser.parseFromString(ret);
                String cookie = xo.get("outCookie");
                ucsCookie = new UcsCookie(cookie, currentTime);
                cookies.put(ucsMgrId, ucsCookie);
                //cookiesTime.put(cookie, currentTime); //This is currentTime on purpose, and not latest time.
            }
            return ucsCookie.getCookie();
        } catch (Exception e) {
            throw new CloudRuntimeException("Cannot get cookie", e);
        }
    }
    
    private List<ComputeBlade> listBlades(Long ucsMgrId) {
        String cookie = getCookie(ucsMgrId);
        UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String cmd = UcsCommands.listComputeBlades(cookie);
        String ret = client.call(cmd);
        return ComputeBlade.fromXmString(ret);
    }

    private List<UcsTemplate> getUcsTemplates(Long ucsMgrId) {
        String cookie = getCookie(ucsMgrId);
        UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
        String cmd = UcsCommands.listTemplates(cookie);
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String res = client.call(cmd);
        List<UcsTemplate> tmps = UcsTemplate.fromXmlString(res);
        return tmps;
    }

    private List<UcsProfile> getUcsProfiles(Long ucsMgrId) {
        String cookie = getCookie(ucsMgrId);
        UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
        String cmd = UcsCommands.listTemplates(cookie);
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String res = client.call(cmd);
        List<UcsTemplate> tmps = UcsTemplate.fromXmlString(res);
        return null;
    }

    @Override
    public ListResponse<UcsProfileResponse> listUcsProfiles(ListUcsProfileCmd cmd) {
        List<UcsProfile> profiles = getUcsProfiles(cmd.getUcsManagerId());
        ListResponse<UcsProfileResponse> response = new ListResponse<UcsProfileResponse>();
        List<UcsProfileResponse> rs = new ArrayList<UcsProfileResponse>();
        for (UcsProfile p : profiles) {
            UcsProfileResponse r = new UcsProfileResponse();
            r.setObjectName("ucsprofile");
            r.setDn(p.getDn());
            rs.add(r);
        }
        response.setResponses(rs);
        return response;
    }

    @Override
    public ListResponse<UcsTemplateResponse> listUcsTemplates(ListUcsTemplatesCmd cmd) {
        List<UcsTemplate> templates = getUcsTemplates(cmd.getUcsManagerId());
        ListResponse<UcsTemplateResponse> response = new ListResponse<UcsTemplateResponse>();
        List<UcsTemplateResponse> rs = new ArrayList<UcsTemplateResponse>();
        for (UcsTemplate t : templates) {
            UcsTemplateResponse r = new UcsTemplateResponse();
            r.setObjectName("ucstemplate");
            r.setDn(t.getDn());
            rs.add(r);
        }
        response.setResponses(rs);
        return response;
    }

    private String cloneProfile(Long ucsMgrId, String srcDn, String newProfileName) {
        UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String cookie = getCookie(ucsMgrId);
        String cmd = UcsCommands.cloneProfile(cookie, srcDn, newProfileName);
        String res = client.call(cmd);
        XmlObject xo = XmlObjectParser.parseFromString(res);
        return xo.get("outConfig.lsServer.dn");
    }


    private boolean isProfileAssociated(Long ucsMgrId, String dn) {
        UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String cookie = getCookie(ucsMgrId);
        String cmd = UcsCommands.configResolveDn(cookie, dn);
        String res = client.call(cmd);
        XmlObject xo = XmlObjectParser.parseFromString(res);

        return xo.get("outConfig.lsServer.assocState").equals("associated");
    }

    private boolean isBladeAssociated(Long ucsMgrId, String dn) {
        UcsManagerVO mgrvo = ucsDao.findById(ucsMgrId);
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String cookie = getCookie(ucsMgrId);
        String cmd = UcsCommands.configResolveDn(cookie, dn);
        String res = client.call(cmd);
        XmlObject xo = XmlObjectParser.parseFromString(res);
        s_logger.debug(String.format("association response is %s", res));
        
        if (xo.get("outConfig.computeBlade.association").equals("none")) {
            throw new CloudRuntimeException(String.format("cannot associated a profile to blade[dn:%s]. please check your UCS manasger for detailed error information", dn));
        }

        return xo.get("outConfig.computeBlade.association").equals("associated");
        //return !xo.get("outConfig.computeBlade.assignedToDn").equals("");
    }

    @Override
    public UcsBladeResponse associateProfileToBlade(AssociateUcsProfileToBladeCmd cmd) {
        SearchCriteriaService<UcsBladeVO, UcsBladeVO> q = SearchCriteria2.create(UcsBladeVO.class);
        q.addAnd(q.getEntity().getUcsManagerId(), Op.EQ, cmd.getUcsManagerId());
        q.addAnd(q.getEntity().getId(), Op.EQ, cmd.getBladeId());
        UcsBladeVO bvo = q.find();
        if (bvo == null) {
            throw new IllegalArgumentException(String.format("cannot find UCS blade[id:%s, ucs manager id:%s]", cmd.getBladeId(), cmd.getUcsManagerId()));
        }

        if (bvo.getHostId() != null) {
            throw new CloudRuntimeException(String.format("blade[id:%s,  dn:%s] has been associated with host[id:%s]", bvo.getId(), bvo.getDn(), bvo.getHostId()));
        }

        UcsManagerVO mgrvo = ucsDao.findById(cmd.getUcsManagerId());
        String cookie = getCookie(cmd.getUcsManagerId());
        String pdn = cloneProfile(mgrvo.getId(), cmd.getProfileDn(), "profile-for-blade-" + bvo.getId());
        String ucscmd = UcsCommands.associateProfileToBlade(cookie, pdn, bvo.getDn());
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String res = client.call(ucscmd);
        int count = 0;
        int timeout = 3600;
        while (count < timeout) {
            if (isBladeAssociated(mgrvo.getId(), bvo.getDn())) {
                break;
            }

            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                throw new CloudRuntimeException(e);
            }

            count += 2;
        }

        if (count >= timeout) {
            throw new CloudRuntimeException(String.format("associating profile[%s] to balde[%s] timeout after 600 seconds", pdn, bvo.getDn()));
        }

        bvo.setProfileDn(pdn);
        bladeDao.update(bvo.getId(), bvo);

        UcsBladeResponse rsp = bladeVOToResponse(bvo);

        s_logger.debug(String.format("successfully associated profile[%s] to blade[%s]", pdn, bvo.getDn()));
        return rsp;
    }

    @Override
    public UcsBladeResponse instantiateTemplateAndAssociateToBlade(InstantiateUcsTemplateAndAssociateToBladeCmd cmd) {
        String profileName = cmd.getProfileName();
        if (profileName == null || "".equals(profileName)) {
            profileName = UUID.randomUUID().toString().replace("-", "");
        }

        SearchCriteriaService<UcsBladeVO, UcsBladeVO> q = SearchCriteria2.create(UcsBladeVO.class);
        q.addAnd(q.getEntity().getUcsManagerId(), Op.EQ, cmd.getUcsManagerId());
        q.addAnd(q.getEntity().getId(), Op.EQ, cmd.getBladeId());
        UcsBladeVO bvo = q.find();
        if (bvo == null) {
            throw new IllegalArgumentException(String.format("cannot find UCS blade[id:%s, ucs manager id:%s]", cmd.getBladeId(), cmd.getUcsManagerId()));
        }

        if (bvo.getHostId() != null) {
            throw new CloudRuntimeException(String.format("blade[id:%s,  dn:%s] has been associated with host[id:%s]", bvo.getId(), bvo.getDn(), bvo.getHostId()));
        }

        UcsManagerVO mgrvo = ucsDao.findById(cmd.getUcsManagerId());
        String cookie = getCookie(cmd.getUcsManagerId());
        String instantiateTemplateCmd = UcsCommands.instantiateTemplate(cookie, cmd.getTemplateDn(), profileName);
        UcsHttpClient http = new UcsHttpClient(mgrvo.getUrl());
        String res = http.call(instantiateTemplateCmd);
        XmlObject xo = XmlObjectParser.parseFromString(res);
        String profileDn = xo.get("outConfig.lsServer.dn");
        String ucscmd = UcsCommands.associateProfileToBlade(cookie, profileDn, bvo.getDn());
        res = http.call(ucscmd);
        int count = 0;
        int timeout = 3600;
        while (count < timeout) {
            if (isBladeAssociated(mgrvo.getId(), bvo.getDn())) {
                break;
            }

            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                throw new CloudRuntimeException(e);
            }

            count += 2;
        }

        if (count >= timeout) {
            throw new CloudRuntimeException(String.format("associating profile[%s] to balde[%s] timeout after 600 seconds", profileDn, bvo.getDn()));
        }

        bvo.setProfileDn(profileDn);
        bladeDao.update(bvo.getId(), bvo);

        UcsBladeResponse rsp = bladeVOToResponse(bvo);

        s_logger.debug(String.format("successfully associated profile[%s] to blade[%s]", profileDn, bvo.getDn()));
        return rsp;
    }

    private String hostIdToUuid(Long hostId) {
        if (hostId == null) {
            return null;
        }
        HostVO vo = hostDao.findById(hostId);
        return vo.getUuid();
    }
    
    private String zoneIdToUuid(Long zoneId) {
        DataCenterVO vo = dcDao.findById(zoneId);
        return vo.getUuid();
    }
    
    private String ucsManagerIdToUuid(Long ucsMgrId) {
        UcsManagerVO vo = ucsDao.findById(ucsMgrId);
        return vo.getUuid();
    }

    @Override
    public ListResponse<UcsManagerResponse> listUcsManager(ListUcsManagerCmd cmd) {
        List<UcsManagerResponse> rsps = new ArrayList<UcsManagerResponse>();
        ListResponse<UcsManagerResponse> response = new ListResponse<UcsManagerResponse>();
    	if (cmd.getId() != null) {
    		UcsManagerVO vo = ucsDao.findById(cmd.getId());
            UcsManagerResponse rsp = new UcsManagerResponse();
            rsp.setObjectName("ucsmanager");
            rsp.setId(vo.getUuid());
            rsp.setName(vo.getName());
            rsp.setUrl(vo.getUrl());
            rsp.setZoneId(zoneIdToUuid(vo.getZoneId()));
            rsps.add(rsp);
            response.setResponses(rsps);
            return response;
    	}
    	
        SearchCriteriaService<UcsManagerVO, UcsManagerVO> serv = SearchCriteria2.create(UcsManagerVO.class);
        serv.addAnd(serv.getEntity().getZoneId(), Op.EQ, cmd.getZoneId());
        List<UcsManagerVO> vos = serv.list();

        for (UcsManagerVO vo : vos) {
            UcsManagerResponse rsp = new UcsManagerResponse();
            rsp.setObjectName("ucsmanager");
            rsp.setId(vo.getUuid());
            rsp.setName(vo.getName());
            rsp.setUrl(vo.getUrl());
            rsp.setZoneId(zoneIdToUuid(vo.getZoneId()));
            rsps.add(rsp);
        }
        response.setResponses(rsps);
        return response;
    }

    private UcsBladeResponse bladeVOToResponse(UcsBladeVO vo) {
        UcsBladeResponse rsp = new UcsBladeResponse();
        rsp.setObjectName("ucsblade");
        rsp.setId(vo.getUuid());
        rsp.setDn(vo.getDn());
        rsp.setHostId(hostIdToUuid(vo.getHostId()));
        rsp.setAssociatedProfileDn(vo.getProfileDn());
        rsp.setUcsManagerId(ucsManagerIdToUuid(vo.getUcsManagerId()));
        return rsp;
    }

    private ListResponse<UcsBladeResponse> listUcsBlades(long mgrId) {
        SearchCriteriaService<UcsBladeVO, UcsBladeVO> serv = SearchCriteria2.create(UcsBladeVO.class);
        serv.addAnd(serv.getEntity().getUcsManagerId(), Op.EQ, mgrId);
        List<UcsBladeVO> vos = serv.list();

        List<UcsBladeResponse> rsps = new ArrayList<UcsBladeResponse>(vos.size());
        for (UcsBladeVO vo : vos) {
            UcsBladeResponse rsp = bladeVOToResponse(vo);
            rsps.add(rsp);
        }

        ListResponse<UcsBladeResponse> response = new ListResponse<UcsBladeResponse>();
        response.setResponses(rsps);

        return response;
    }

    @Override
    public ListResponse<UcsBladeResponse> listUcsBlades(ListUcsBladeCmd cmd) {
        return listUcsBlades(cmd.getUcsManagerId());
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void setConfigParams(Map<String, Object> params) {
        this.params = params;
    }

    @Override
    public Map<String, Object> getConfigParams() {
        return params;
    }

    @Override
    public int getRunLevel() {
        return runLevel;
    }

    @Override
    public void setRunLevel(int level) {
        runLevel = level;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmds = new ArrayList<Class<?>>();
        cmds.add(ListUcsBladeCmd.class);
        cmds.add(ListUcsManagerCmd.class);
        cmds.add(ListUcsProfileCmd.class);
        cmds.add(AddUcsManagerCmd.class);
        cmds.add(AssociateUcsProfileToBladeCmd.class);
        cmds.add(DeleteUcsManagerCmd.class);
        cmds.add(DisassociateUcsProfileCmd.class);
        cmds.add(RefreshUcsBladesCmd.class);
        cmds.add(ListUcsTemplatesCmd.class);
        cmds.add(InstantiateUcsTemplateAndAssociateToBladeCmd.class);
        return cmds;
    }

	@Override
	public void deleteUcsManager(Long id) {
        SearchCriteriaService<UcsBladeVO, UcsBladeVO> serv = SearchCriteria2.create(UcsBladeVO.class);
        serv.addAnd(serv.getEntity().getUcsManagerId(), Op.EQ, id);
        List<UcsBladeVO> vos = serv.list();
        for (UcsBladeVO vo : vos) {
        	bladeDao.remove(vo.getId());
        }
		ucsDao.remove(id);
	}

    @Override
    @DB
    public ListResponse<UcsBladeResponse> refreshBlades(Long mgrId) {
        SyncBladesThread synct = new SyncBladesThread();
        synct.run();

        UcsManagerVO mgrvo = ucsDao.findById(mgrId);
        List<ComputeBlade> blades = listBlades(mgrvo.getId());
        for (ComputeBlade b : blades) {
            SearchCriteria2<UcsBladeVO, UcsBladeVO> q = SearchCriteria2.create(UcsBladeVO.class, UcsBladeVO.class);
            q.addAnd(q.getEntity().getDn(), Op.EQ, b.getDn());
            UcsBladeVO vo = q.find();
            if (vo == null) {
                vo = new UcsBladeVO();
                vo.setProfileDn("".equals(b.getAssignedToDn()) ? null : b.getAssignedToDn());
                vo.setDn(b.getDn());
                vo.setUuid(UUID.randomUUID().toString());
                vo.setUcsManagerId(mgrId);
                bladeDao.persist(vo);
            } else {
                vo.setProfileDn("".equals(b.getAssignedToDn()) ? null : b.getAssignedToDn());
                bladeDao.update(vo.getId(), vo);
            }
        }

        return listUcsBlades(mgrId);
    }

    @Override
    public UcsBladeResponse disassociateProfile(DisassociateUcsProfileCmd cmd) {
        UcsBladeVO blade = bladeDao.findById(cmd.getBladeId());
        UcsManagerVO mgrvo = ucsDao.findById(blade.getUcsManagerId());
        UcsHttpClient client = new UcsHttpClient(mgrvo.getUrl());
        String cookie = getCookie(mgrvo.getId());
        String call = UcsCommands.disassociateProfileFromBlade(cookie, blade.getProfileDn());
        client.call(call);
        if (cmd.isDeleteProfile()) {
            call = UcsCommands.deleteProfile(cookie, blade.getProfileDn());
            client = new UcsHttpClient(mgrvo.getUrl());
            client.call(call);
        }
        blade.setProfileDn(null);
        bladeDao.update(blade.getId(), blade);
        UcsBladeResponse rsp = bladeVOToResponse(blade);
        return rsp;
    }
}
