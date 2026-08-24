package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalysisServiceTest {
    @Test
    void completesAndPersistsOnlySanitizedResults() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=new TargetApplication("p","app","http://localhost","http://localhost/login","enc-user","enc-pass");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt("enc-user")).thenReturn("user"); when(protector.decrypt("enc-pass")).thenReturn("password");
        when(adapter.analyze(app,"user","password")).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home","Home",List.of(new ScreenAnalysisAdapter.DetectedElement("button","button:nth-of-type(1)","Save",ActionClassification.MUTATING)),new byte[]{1,2}));
        when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED); verify(screenshots).save(any(Screenshot.class)); verify(elements).save(any(UIElement.class));
    }

    @Test
    void failsClosedAndDoesNotExposeAdapterSecrets() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); TargetApplication app=new TargetApplication("p","app","http://localhost","http://localhost/login","u","p");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class); when(adapter.analyze(any(),any(),any())).thenThrow(new RuntimeException("password=supersecret"));
        Analysis result=new AnalysisService(analyses,mock(PageRepository.class),mock(UIElementRepository.class),mock(ScreenshotRepository.class),apps,mock(CredentialProtector.class),adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.FAILED); assertThat(result.getFailureMessage()).doesNotContain("supersecret");
    }
}
