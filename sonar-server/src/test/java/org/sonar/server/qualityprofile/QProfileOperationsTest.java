/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2013 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.qualityprofile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import org.apache.ibatis.session.SqlSession;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.sonar.api.profiles.ProfileImporter;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.rule.Severity;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.rules.Rule;
import org.sonar.api.rules.RulePriority;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.ValidationMessages;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.preview.PreviewCache;
import org.sonar.core.properties.PropertiesDao;
import org.sonar.core.properties.PropertyDto;
import org.sonar.core.qualityprofile.db.*;
import org.sonar.core.rule.RuleDao;
import org.sonar.core.rule.RuleDto;
import org.sonar.core.rule.RuleParamDto;
import org.sonar.server.configuration.ProfilesManager;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.rule.ProfileRules;
import org.sonar.server.rule.RuleRegistry;
import org.sonar.server.user.MockUserSession;

import java.io.Reader;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class QProfileOperationsTest {

  @Mock
  MyBatis myBatis;

  @Mock
  SqlSession session;

  @Mock
  QualityProfileDao qualityProfileDao;

  @Mock
  ActiveRuleDao activeRuleDao;

  @Mock
  RuleDao ruleDao;

  @Mock
  PropertiesDao propertiesDao;

  @Mock
  PreviewCache dryRunCache;

  @Mock
  RuleRegistry ruleRegistry;

  @Mock
  ProfileRules profileRules;

  @Mock
  ProfilesManager profilesManager;

  @Mock
  System2 system;

  List<ProfileImporter> importers = newArrayList();

  QProfileOperations operations;

  Integer currentId = 1;

  @Before
  public void setUp() throws Exception {
    when(myBatis.openSession()).thenReturn(session);

    // Associate an id when inserting an object to simulate the db id generator
    doAnswer(new Answer() {
      public Object answer(InvocationOnMock invocation) {
        Object[] args = invocation.getArguments();
        ActiveRuleDto dto = (ActiveRuleDto) args[0];
        dto.setId(currentId++);
        return null;
      }
    }).when(activeRuleDao).insert(any(ActiveRuleDto.class), any(SqlSession.class));

    operations = new QProfileOperations(myBatis, qualityProfileDao, activeRuleDao, ruleDao, propertiesDao, importers, dryRunCache, ruleRegistry, profilesManager,
      profileRules, system);
  }

  @Test
  public void create_profile() throws Exception {
    NewProfileResult result = operations.newProfile("Default", "java", Maps.<String, String>newHashMap(), MockUserSession.create().setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
    assertThat(result.profile().name()).isEqualTo("Default");
    assertThat(result.profile().language()).isEqualTo("java");

    verify(qualityProfileDao).insert(any(QualityProfileDto.class), eq(session));

    ArgumentCaptor<QualityProfileDto> profileArgument = ArgumentCaptor.forClass(QualityProfileDto.class);
    verify(qualityProfileDao).insert(profileArgument.capture(), eq(session));
    assertThat(profileArgument.getValue().getName()).isEqualTo("Default");
    assertThat(profileArgument.getValue().getLanguage()).isEqualTo("java");
    assertThat(profileArgument.getValue().getVersion()).isEqualTo(1);
    assertThat(profileArgument.getValue().isUsed()).isFalse();
  }

  @Test
  public void fail_to_create_profile_without_profile_admin_permission() throws Exception {
    try {
      operations.newProfile("Default", "java", Maps.<String, String>newHashMap(), MockUserSession.create());
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(ForbiddenException.class);
    }
    verifyNoMoreInteractions(qualityProfileDao);
    verify(session, never()).commit();
  }

  @Test
  public void create_profile_from_xml_plugin() throws Exception {
    RulesProfile profile = RulesProfile.create("Default", "java");
    Rule rule = Rule.create("pmd", "rule1");
    rule.createParameter("max");
    rule.setId(10);
    ActiveRule activeRule = profile.activateRule(rule, RulePriority.BLOCKER);
    activeRule.setParameter("max", "10");

    Map<String, String> xmlProfilesByPlugin = newHashMap();
    xmlProfilesByPlugin.put("pmd", "<xml/>");
    ProfileImporter importer = mock(ProfileImporter.class);
    when(importer.getKey()).thenReturn("pmd");
    when(importer.importProfile(any(Reader.class), any(ValidationMessages.class))).thenReturn(profile);
    importers.add(importer);

    operations.newProfile("Default", "java", xmlProfilesByPlugin, MockUserSession.create().setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
    verify(session).commit();

    ArgumentCaptor<QualityProfileDto> profileArgument = ArgumentCaptor.forClass(QualityProfileDto.class);
    verify(qualityProfileDao).insert(profileArgument.capture(), eq(session));
    assertThat(profileArgument.getValue().getName()).isEqualTo("Default");
    assertThat(profileArgument.getValue().getLanguage()).isEqualTo("java");

    ArgumentCaptor<ActiveRuleDto> activeRuleArgument = ArgumentCaptor.forClass(ActiveRuleDto.class);
    verify(activeRuleDao).insert(activeRuleArgument.capture(), eq(session));
    assertThat(activeRuleArgument.getValue().getRulId()).isEqualTo(10);
    assertThat(activeRuleArgument.getValue().getSeverity()).isEqualTo(4);

    ArgumentCaptor<ActiveRuleParamDto> activeRuleParamArgument = ArgumentCaptor.forClass(ActiveRuleParamDto.class);
    verify(activeRuleDao).insert(activeRuleParamArgument.capture(), eq(session));
    assertThat(activeRuleParamArgument.getValue().getKey()).isEqualTo("max");
    assertThat(activeRuleParamArgument.getValue().getValue()).isEqualTo("10");

    verify(ruleRegistry).bulkIndexActiveRules(anyListOf(ActiveRuleDto.class), any(Multimap.class));
  }

  @Test
  public void fail_to_create_profile_from_xml_plugin_if_error() throws Exception {
    try {
      Map<String, String> xmlProfilesByPlugin = newHashMap();
      xmlProfilesByPlugin.put("pmd", "<xml/>");
      ProfileImporter importer = mock(ProfileImporter.class);
      when(importer.getKey()).thenReturn("pmd");
      importers.add(importer);

      doAnswer(new Answer() {
        public Object answer(InvocationOnMock invocation) {
          Object[] args = invocation.getArguments();
          ValidationMessages validationMessages = (ValidationMessages) args[1];
          validationMessages.addErrorText("error!");
          return null;
        }
      }).when(importer).importProfile(any(Reader.class), any(ValidationMessages.class));

      operations.newProfile("Default", "java", xmlProfilesByPlugin, MockUserSession.create().setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(BadRequestException.class);
    }
    verify(session, never()).commit();
  }

  @Test
  public void rename_profile() throws Exception {
    QualityProfileDto qualityProfile = new QualityProfileDto().setId(1).setName("Default").setLanguage("java");
    operations.renameProfile(qualityProfile, "Default profile", MockUserSession.create().setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<QualityProfileDto> profileArgument = ArgumentCaptor.forClass(QualityProfileDto.class);
    verify(qualityProfileDao).update(profileArgument.capture());

    assertThat(profileArgument.getValue().getName()).isEqualTo("Default profile");
    assertThat(profileArgument.getValue().getLanguage()).isEqualTo("java");
  }

  @Test
  public void update_default_profile() throws Exception {
    QualityProfileDto qualityProfile = new QualityProfileDto().setId(1).setName("My profile").setLanguage("java");

    operations.setDefaultProfile(qualityProfile, MockUserSession.create().setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<PropertyDto> argumentCaptor = ArgumentCaptor.forClass(PropertyDto.class);
    verify(propertiesDao).setProperty(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getKey()).isEqualTo("sonar.profile.java");
    assertThat(argumentCaptor.getValue().getValue()).isEqualTo("My profile");
  }

  @Test
  public void activate_rule() throws Exception {
    QualityProfileDto qualityProfile = new QualityProfileDto().setId(1).setName("My profile").setLanguage("java");
    Rule rule = Rule.create().setRepositoryKey("squid").setKey("AvoidCycle");
    rule.setId(10);
    when(ruleDao.selectParameters(eq(10), eq(session))).thenReturn(newArrayList(new RuleParamDto().setId(20).setName("max").setDefaultValue("10")));
    when(profileRules.getFromActiveRuleId(anyInt())).thenReturn(mock(QProfileRule.class));
    final int idActiveRuleToUpdate = 42;
    final int idActiveRuleToDelete = 24;
    RuleInheritanceActions inheritanceActions = new RuleInheritanceActions()
      .addToIndex(idActiveRuleToUpdate)
      .addToDelete(idActiveRuleToDelete);
    when(profilesManager.activated(eq(1), anyInt(), eq("nicolas"))).thenReturn(inheritanceActions);
    when(activeRuleDao.selectByIds(anyList(), isA(SqlSession.class))).thenReturn(ImmutableList.<ActiveRuleDto> of(mock(ActiveRuleDto.class)));
    when(activeRuleDao.selectParamsByRuleIds(anyList(), isA(SqlSession.class))).thenReturn(ImmutableList.<ActiveRuleParamDto> of(mock(ActiveRuleParamDto.class)));

    RuleActivationResult result = operations.createActiveRule(qualityProfile, rule, Severity.CRITICAL, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
    assertThat(result.profile()).isNotNull();
    assertThat(result.rule()).isNotNull();
    assertThat(result.rule().activeRuleId()).isNotNull();

    ArgumentCaptor<ActiveRuleDto> activeRuleArgument = ArgumentCaptor.forClass(ActiveRuleDto.class);
    verify(activeRuleDao).insert(activeRuleArgument.capture(), eq(session));
    assertThat(activeRuleArgument.getValue().getRulId()).isEqualTo(10);
    assertThat(activeRuleArgument.getValue().getSeverity()).isEqualTo(3);

    ArgumentCaptor<ActiveRuleParamDto> activeRuleParamArgument = ArgumentCaptor.forClass(ActiveRuleParamDto.class);
    verify(activeRuleDao).insert(activeRuleParamArgument.capture(), eq(session));
    assertThat(activeRuleParamArgument.getValue().getKey()).isEqualTo("max");
    assertThat(activeRuleParamArgument.getValue().getValue()).isEqualTo("10");

    verify(session).commit();
    verify(profileRules).getFromActiveRuleId(anyInt());
    verify(profilesManager).activated(eq(1), anyInt(), eq("nicolas"));
    verify(ruleRegistry).bulkIndexActiveRules(anyList(), isA(Multimap.class));
  }

  @Test
  public void update_severity() throws Exception {
    QualityProfileDto qualityProfile = new QualityProfileDto().setId(1).setName("My profile").setLanguage("java");
    Rule rule = Rule.create().setRepositoryKey("squid").setKey("AvoidCycle");
    rule.setId(10);
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1);
    when(profileRules.getFromActiveRuleId(anyInt())).thenReturn(mock(QProfileRule.class));
    when(profilesManager.ruleSeverityChanged(eq(1), eq(5), eq(RulePriority.MINOR), eq(RulePriority.MAJOR), eq("nicolas"))).thenReturn(new RuleInheritanceActions());

    RuleActivationResult result = operations.updateSeverity(qualityProfile, activeRule, Severity.MAJOR, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
    assertThat(result.profile()).isNotNull();
    assertThat(result.rule()).isNotNull();
    assertThat(result.rule().activeRuleId()).isNotNull();

    verify(activeRuleDao).update(eq(activeRule), eq(session));
    verify(session).commit();
    verify(profileRules).getFromActiveRuleId(anyInt());
    verify(profilesManager).ruleSeverityChanged(eq(1), eq(5), eq(RulePriority.MINOR), eq(RulePriority.MAJOR), eq("nicolas"));
  }

  @Test
  public void deactivate_rule() throws Exception {
    QualityProfileDto qualityProfile = new QualityProfileDto().setId(1).setName("My profile").setLanguage("java");
    Rule rule = Rule.create().setRepositoryKey("squid").setKey("AvoidCycle");
    rule.setId(10);
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1);
    when(activeRuleDao.selectByProfileAndRule(1, 10)).thenReturn(activeRule);
    when(profileRules.getFromRuleId(anyInt())).thenReturn(mock(QProfileRule.class));
    when(profilesManager.deactivated(eq(1), anyInt(), eq("nicolas"))).thenReturn(new RuleInheritanceActions());

    RuleActivationResult result = operations.deactivateRule(qualityProfile, rule, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
    assertThat(result.profile()).isNotNull();
    assertThat(result.rule()).isNotNull();

    verify(activeRuleDao).delete(eq(5), eq(session));
    verify(activeRuleDao).deleteParameters(eq(5), eq(session));
    verify(session).commit();
    verify(profileRules).getFromRuleId(anyInt());
    verify(profilesManager).deactivated(eq(1), anyInt(), eq("nicolas"));
  }

  @Test
  public void fail_to_deactivate_rule_if_no_active_rule_on_profile() throws Exception {
    QualityProfileDto qualityProfile = new QualityProfileDto().setId(1).setName("My profile").setLanguage("java");
    Rule rule = Rule.create().setRepositoryKey("squid").setKey("AvoidCycle");
    rule.setId(10);
    when(activeRuleDao.selectByProfileAndRule(1, 10)).thenReturn(null);

    try {
      operations.deactivateRule(qualityProfile, rule, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(BadRequestException.class);
    }
    verify(activeRuleDao, never()).update(any(ActiveRuleDto.class), eq(session));
    verify(session, never()).commit();
    verifyZeroInteractions(profilesManager);
  }

  @Test
  public void update_active_rule_param() throws Exception {
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1);
    ActiveRuleParamDto activeRuleParam = new ActiveRuleParamDto().setId(100).setActiveRuleId(5).setKey("max").setValue("20");
    when(profilesManager.ruleParamChanged(eq(1), eq(5), eq("max"), eq("20"), eq("30"), eq("nicolas"))).thenReturn(new RuleInheritanceActions());

    operations.updateActiveRuleParam(activeRule, activeRuleParam, "30", MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<ActiveRuleParamDto> argumentCaptor = ArgumentCaptor.forClass(ActiveRuleParamDto.class);
    verify(activeRuleDao).update(argumentCaptor.capture(), eq(session));
    assertThat(argumentCaptor.getValue().getId()).isEqualTo(100);
    assertThat(argumentCaptor.getValue().getValue()).isEqualTo("30");

    verify(profilesManager).ruleParamChanged(eq(1), eq(5), eq("max"), eq("20"), eq("30"), eq("nicolas"));
  }

  @Test
  public void remove_active_rule_param() throws Exception {
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1);
    ActiveRuleParamDto activeRuleParam = new ActiveRuleParamDto().setId(100).setActiveRuleId(5).setKey("max").setValue("20");
    when(profilesManager.ruleParamChanged(eq(1), eq(5), eq("max"), eq("20"), eq((String) null), eq("nicolas"))).thenReturn(new RuleInheritanceActions());

    operations.deleteActiveRuleParam(activeRule, activeRuleParam, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    verify(activeRuleDao).deleteParameter(100, session);
    verify(profilesManager).ruleParamChanged(eq(1), eq(5), eq("max"), eq("20"), eq((String) null), eq("nicolas"));
  }

  @Test
  public void create_active_rule_param() throws Exception {
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1);
    RuleParamDto ruleParam = new RuleParamDto().setRuleId(10).setName("max").setDefaultValue("20");
    when(ruleDao.selectParamByRuleAndKey(10, "max", session)).thenReturn(ruleParam);
    when(profilesManager.ruleParamChanged(eq(1), eq(5), eq("max"), eq((String) null), eq("30"), eq("nicolas"))).thenReturn(new RuleInheritanceActions());

    operations.createActiveRuleParam(activeRule, "max", "30", MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<ActiveRuleParamDto> argumentCaptor = ArgumentCaptor.forClass(ActiveRuleParamDto.class);
    verify(activeRuleDao).insert(argumentCaptor.capture(), eq(session));
    assertThat(argumentCaptor.getValue().getKey()).isEqualTo("max");
    assertThat(argumentCaptor.getValue().getValue()).isEqualTo("30");
    assertThat(argumentCaptor.getValue().getActiveRuleId()).isEqualTo(5);

    verify(profilesManager).ruleParamChanged(eq(1), eq(5), eq("max"), eq((String) null), eq("30"), eq("nicolas"));
  }

  @Test
  public void fail_to_create_active_rule_if_no_rule_param() throws Exception {
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1);
    when(ruleDao.selectParamByRuleAndKey(10, "max", session)).thenReturn(null);
    try {
      operations.createActiveRuleParam(activeRule, "max", "30", MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalArgumentException.class);
    }
    verify(activeRuleDao, never()).insert(any(ActiveRuleParamDto.class), eq(session));
    verifyZeroInteractions(profilesManager);
  }

  @Test
  public void update_active_rule_note_when_no_note() throws Exception {
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1).setNoteCreatedAt(null).setNoteData(null);

    long now = System.currentTimeMillis();
    doReturn(now).when(system).now();

    operations.updateActiveRuleNote(activeRule, "My note", MockUserSession.create().setLogin("nicolas").setName("Nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<ActiveRuleDto> argumentCaptor = ArgumentCaptor.forClass(ActiveRuleDto.class);
    verify(activeRuleDao).update(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getNoteData()).isEqualTo("My note");
    assertThat(argumentCaptor.getValue().getNoteUserLogin()).isEqualTo("nicolas");
    assertThat(argumentCaptor.getValue().getNoteCreatedAt().getTime()).isEqualTo(now);
    assertThat(argumentCaptor.getValue().getNoteUpdatedAt().getTime()).isEqualTo(now);
  }

  @Test
  public void update_active_rule_note_when_already_note() throws Exception {
    Date createdAt = DateUtils.parseDate("2013-12-20");
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1)
      .setNoteCreatedAt(createdAt).setNoteData("My previous note").setNoteUserLogin("nicolas");

    long now = System.currentTimeMillis();
    doReturn(now).when(system).now();

    operations.updateActiveRuleNote(activeRule, "My new note", MockUserSession.create().setLogin("guy").setName("Guy").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<ActiveRuleDto> argumentCaptor = ArgumentCaptor.forClass(ActiveRuleDto.class);
    verify(activeRuleDao).update(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getNoteData()).isEqualTo("My new note");
    assertThat(argumentCaptor.getValue().getNoteUserLogin()).isEqualTo("nicolas");
    assertThat(argumentCaptor.getValue().getNoteCreatedAt()).isEqualTo(createdAt);
    assertThat(argumentCaptor.getValue().getNoteUpdatedAt().getTime()).isEqualTo(now);
  }

  @Test
  public void delete_active_rule_note() throws Exception {
    Date createdAt = DateUtils.parseDate("2013-12-20");
    ActiveRuleDto activeRule = new ActiveRuleDto().setId(5).setProfileId(1).setRuleId(10).setSeverity(1)
      .setNoteData("My note").setNoteUserLogin("nicolas").setNoteCreatedAt(createdAt).setNoteUpdatedAt(createdAt);

    long now = System.currentTimeMillis();
    doReturn(now).when(system).now();

    operations.deleteActiveRuleNote(activeRule, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<ActiveRuleDto> argumentCaptor = ArgumentCaptor.forClass(ActiveRuleDto.class);
    verify(activeRuleDao).update(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getNoteData()).isNull();
    assertThat(argumentCaptor.getValue().getNoteUserLogin()).isNull();
    assertThat(argumentCaptor.getValue().getNoteCreatedAt()).isNull();
    assertThat(argumentCaptor.getValue().getNoteUpdatedAt()).isNull();
  }

  @Test
  public void update_rule_note_when_no_note() throws Exception {
    RuleDto rule = new RuleDto().setId(10).setNoteCreatedAt(null).setNoteData(null);

    long now = System.currentTimeMillis();
    doReturn(now).when(system).now();

    operations.updateRuleNote(rule, "My note", MockUserSession.create().setLogin("nicolas").setName("Nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<RuleDto> argumentCaptor = ArgumentCaptor.forClass(RuleDto.class);
    verify(ruleDao).update(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getNoteData()).isEqualTo("My note");
    assertThat(argumentCaptor.getValue().getNoteUserLogin()).isEqualTo("nicolas");
    assertThat(argumentCaptor.getValue().getNoteCreatedAt().getTime()).isEqualTo(now);
    assertThat(argumentCaptor.getValue().getNoteUpdatedAt().getTime()).isEqualTo(now);
  }

  @Test
  public void update_rule_note_when_already_note() throws Exception {
    Date createdAt = DateUtils.parseDate("2013-12-20");
    RuleDto rule = new RuleDto().setId(10).setNoteCreatedAt(createdAt).setNoteData("My previous note").setNoteUserLogin("nicolas");

    long now = System.currentTimeMillis();
    doReturn(now).when(system).now();

    operations.updateRuleNote(rule, "My new note", MockUserSession.create().setLogin("guy").setName("Guy").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<RuleDto> argumentCaptor = ArgumentCaptor.forClass(RuleDto.class);
    verify(ruleDao).update(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getNoteData()).isEqualTo("My new note");
    assertThat(argumentCaptor.getValue().getNoteUserLogin()).isEqualTo("nicolas");
    assertThat(argumentCaptor.getValue().getNoteCreatedAt()).isEqualTo(createdAt);
    assertThat(argumentCaptor.getValue().getNoteUpdatedAt().getTime()).isEqualTo(now);
  }

  @Test
  public void delete_rule_note() throws Exception {
    Date createdAt = DateUtils.parseDate("2013-12-20");
    RuleDto rule = new RuleDto().setId(10).setNoteData("My note").setNoteUserLogin("nicolas").setNoteCreatedAt(createdAt).setNoteUpdatedAt(createdAt);

    long now = System.currentTimeMillis();
    doReturn(now).when(system).now();

    operations.deleteRuleNote(rule, MockUserSession.create().setName("nicolas").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN));

    ArgumentCaptor<RuleDto> argumentCaptor = ArgumentCaptor.forClass(RuleDto.class);
    verify(ruleDao).update(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getNoteData()).isNull();
    assertThat(argumentCaptor.getValue().getNoteUserLogin()).isNull();
    assertThat(argumentCaptor.getValue().getNoteCreatedAt()).isNull();
    assertThat(argumentCaptor.getValue().getNoteUpdatedAt()).isNull();
  }

}
