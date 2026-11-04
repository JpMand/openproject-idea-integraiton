package com.github.jpmand.openprojectideaintegraiton.tasks

import com.intellij.tasks.TaskRepositoryType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil

class OpenProjectRepositoryTest : BasePlatformTestCase() {

    private fun type(): OpenProjectRepositoryType =
        TaskRepositoryType.getRepositoryTypes().filterIsInstance<OpenProjectRepositoryType>().single()

    fun `test repository type is registered`() {
        assertEquals("OpenProject", type().name)
        assertEquals(OpenProjectRepository::class.java, type().repositoryClass)
    }

    fun `test settings survive xml serialization without the token`() {
        val repository = type().createRepository() as OpenProjectRepository
        repository.url = "https://op.example.com"
        repository.password = "secret-token"
        repository.projectId = 12
        repository.projectName = "Demo"
        repository.assignedToMeOnly = true
        repository.savedQueryId = 5
        repository.fullTextSearch = true

        val element = XmlSerializer.serialize(repository)
        val xml = JDOMUtil.write(element)
        assertEquals("OpenProject", element.name)
        assertFalse(xml, xml.contains("secret-token"))

        val restored = XmlSerializer.deserialize(element, OpenProjectRepository::class.java)
        assertEquals("https://op.example.com", restored.url)
        assertEquals(12L, restored.projectId)
        assertEquals("Demo", restored.projectName)
        assertTrue(restored.assignedToMeOnly)
        assertEquals(5L, restored.savedQueryId)
        assertTrue(restored.fullTextSearch)
        assertEquals(OpenProjectRepository.API_KEY_USER, restored.username)
    }

    fun `test clone and equals include the OpenProject settings`() {
        val repository = type().createRepository() as OpenProjectRepository
        repository.url = "https://op.example.com"
        repository.projectId = 3
        val copy = repository.clone()
        assertEquals(repository, copy)
        copy.assignedToMeOnly = true
        assertFalse(repository == copy)
    }

    fun `test editor builds and applies the OpenProject settings`() {
        val repository = type().createRepository() as OpenProjectRepository
        repository.projectId = 4
        repository.projectName = "Demo"
        repository.assignedToMeOnly = true
        val editor = type().createEditor(repository, project) {} as OpenProjectRepositoryEditor
        try {
            assertNotNull(editor.createComponent())
            editor.apply()
            assertEquals(4L, repository.projectId)
            assertEquals("Demo", repository.projectName)
            assertTrue(repository.assignedToMeOnly)
            assertEquals(OpenProjectRepository.API_KEY_USER, repository.username)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    fun `test presentable name shows host and project`() {
        val repository = type().createRepository() as OpenProjectRepository
        repository.url = "https://op.example.com/"
        assertEquals("op.example.com", repository.presentableName)
        repository.projectName = "Demo"
        assertEquals("op.example.com / Demo", repository.presentableName)
    }
}
