/**
 * Copyright (C) 2005-2014 Rivet Logic Corporation.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; version 3 of the License.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

package com.rivetlogic.evernote.portlet;

import static com.rivetlogic.evernote.util.EvernoteConstants.ACCESS_TOKEN;
import static com.rivetlogic.evernote.util.EvernoteConstants.EVERNOTE_SERVICE;
import static com.rivetlogic.evernote.util.EvernoteConstants.NEED_AUTHORIZE;
import static com.rivetlogic.evernote.util.EvernoteConstants.NOTES_LOADED;
import static com.rivetlogic.evernote.util.EvernoteConstants.NOTES_LOADED_DEFAULT_VALUE;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletException;
import javax.portlet.PortletPreferences;
import javax.portlet.PortletResponse;
import javax.portlet.PortletSession;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.evernote.auth.EvernoteAuth;
import com.evernote.clients.ClientFactory;
import com.evernote.clients.NoteStoreClient;
import com.evernote.edam.error.EDAMNotFoundException;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.type.Note;
import com.evernote.edam.type.NoteSortOrder;
import com.evernote.edam.type.Notebook;
import com.evernote.thrift.TException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import com.liferay.util.bridges.mvc.MVCPortlet;
import com.rivetlogic.evernote.exception.NoNoteException;
import com.rivetlogic.evernote.util.EvernoteUtil;

public class EvernotePortlet extends MVCPortlet {

	private static final Log LOG = LogFactoryUtil.getLog(EvernotePortlet.class);

	@Override
	public void doView(RenderRequest renderRequest,
			RenderResponse renderResponse) throws PortletException, IOException {
		PortletSession portletSession = renderRequest.getPortletSession();

		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(renderRequest);
		ThemeDisplay themeDisplay = (ThemeDisplay) request
				.getAttribute(WebKeys.THEME_DISPLAY);
		if (themeDisplay.isSignedIn()) {
			if (((String) portletSession.getAttribute(ACCESS_TOKEN)) != null) {
				renderRequest.setAttribute(ACCESS_TOKEN,
						(String) portletSession.getAttribute(ACCESS_TOKEN));
				renderRequest.setAttribute(NEED_AUTHORIZE, Boolean.FALSE);
			} else {
				renderRequest.setAttribute(NEED_AUTHORIZE, Boolean.TRUE);
				try {
					EvernoteUtil.authenticateEvernote(renderRequest,
							portletSession, themeDisplay);
				} catch (SystemException e) {
					LOG.error(e);
					SessionErrors.add(request, SystemException.class);
				}
			}
		}

		super.doView(renderRequest, renderResponse);
	}

	@Override
	public void serveResource(ResourceRequest request, ResourceResponse response) {

		String cmd = ParamUtil.getString(request, "cmd");

		if ("loadNotes".equals(cmd)) {
			JSONArray resultJsonArray = JSONFactoryUtil.createJSONArray();
			resultJsonArray = loadNotes(request);
			returnJSON(response, resultJsonArray);
		}

		else {
			JSONObject resultJsonObject = JSONFactoryUtil.createJSONObject();
			if ("loadMoreNotes".equals(cmd)) {
				resultJsonObject = loadMoreNotes(request);
			} else if ("selectNote".equals(cmd)) {
				resultJsonObject = selectNote(request);
			}
			returnJSON(response, resultJsonObject);
		}

	}

	public JSONArray loadNotes(ResourceRequest resourceRequest) {
		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(resourceRequest);

		PortletSession portletSession = resourceRequest.getPortletSession();
		PortletPreferences prefs = resourceRequest.getPreferences();
		String accessToken = (String) portletSession.getAttribute(ACCESS_TOKEN);
		int notesLoaded = GetterUtil.getInteger(prefs.getValue(NOTES_LOADED,
				NOTES_LOADED_DEFAULT_VALUE));

		JSONArray jsonArray = JSONFactoryUtil.createJSONArray();

		if (accessToken != null && !accessToken.isEmpty()) {
			EvernoteAuth evernoteAuth = new EvernoteAuth(EVERNOTE_SERVICE,
					accessToken);
			NoteStoreClient noteStoreClient;
			try {
				noteStoreClient = new ClientFactory(evernoteAuth)
						.createNoteStoreClient();
				for (Notebook notebook : noteStoreClient.listNotebooks()) {
					JSONObject notebooks = JSONFactoryUtil.createJSONObject();
					notebooks.put("name", notebook.getName());
					notebooks.put("guid", notebook.getGuid());

					NoteFilter filter = new NoteFilter();
					filter.setNotebookGuid(notebook.getGuid());
					filter.setOrder(NoteSortOrder.CREATED.getValue());

					List<Note> notes = noteStoreClient.findNotes(filter, 0,
							notesLoaded).getNotes();
					JSONArray noteList = JSONFactoryUtil.createJSONArray();
					for (Note note : notes) {
						JSONObject jsonNote = JSONFactoryUtil
								.createJSONObject();
						jsonNote.put("title", note.getTitle());
						jsonNote.put("guid", note.getGuid());
						noteList.put(jsonNote);
					}
					notebooks.put("noteList", noteList);
					notebooks
							.put("loadMore",
									noteStoreClient.findNotes(filter, 0,
											notesLoaded + 1).getNotesSize() > notesLoaded);

					jsonArray.put(notebooks);
				}
			} catch (EDAMUserException e) {
				LOG.error(e);
				SessionErrors.add(request, EDAMUserException.class);
			} catch (EDAMSystemException e) {
				LOG.error(e);
				SessionErrors.add(request, EDAMSystemException.class);
			} catch (TException e) {
				LOG.error(e);
				SessionErrors.add(request, TException.class);
			} catch (EDAMNotFoundException e) {
				LOG.error(e);
				SessionErrors.add(request, EDAMNotFoundException.class);
			}

		}

		return jsonArray;
	}

	public JSONObject loadMoreNotes(ResourceRequest resourceRequest) {
		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(resourceRequest);

		PortletSession portletSession = resourceRequest.getPortletSession();
		PortletPreferences prefs = resourceRequest.getPreferences();
		String accessToken = (String) portletSession.getAttribute(ACCESS_TOKEN);
		int notesLoaded = GetterUtil.getInteger(prefs.getValue(NOTES_LOADED,
				NOTES_LOADED_DEFAULT_VALUE));

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		String notebookGuid = ParamUtil.getString(resourceRequest,
				"notebookGuid");
		Integer currentNotes = ParamUtil.getInteger(resourceRequest, "countLI") - 1;

		EvernoteAuth evernoteAuth = new EvernoteAuth(EVERNOTE_SERVICE,
				accessToken);
		NoteStoreClient noteStoreClient;
		try {
			noteStoreClient = new ClientFactory(evernoteAuth)
					.createNoteStoreClient();
			NoteFilter filter = new NoteFilter();
			filter.setNotebookGuid(notebookGuid);
			filter.setOrder(NoteSortOrder.CREATED.getValue());

			int notesToLoad = currentNotes + notesLoaded;
			List<Note> notes = noteStoreClient
					.findNotes(filter, 0, notesToLoad).getNotes();

			JSONArray noteList = JSONFactoryUtil.createJSONArray();
			while (notes.size() > currentNotes) {
				JSONObject jsonNote = JSONFactoryUtil.createJSONObject();

				Note note = notes.get(currentNotes);
				jsonNote.put("title", note.getTitle());
				jsonNote.put("guid", note.getGuid());

				noteList.put(jsonNote);
				currentNotes++;
			}

			jsonObject.put("noteList", noteList);
			jsonObject.put("loadMore",
					noteStoreClient.findNotes(filter, 0, notesToLoad + 1)
							.getNotesSize() > notesToLoad);
		} catch (EDAMUserException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		} catch (EDAMSystemException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		} catch (TException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		} catch (EDAMNotFoundException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		}

		return jsonObject;
	}

	public JSONObject selectNote(ResourceRequest resourceRequest) {
		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(resourceRequest);

		PortletSession portletSession = resourceRequest.getPortletSession();
		String accessToken = (String) portletSession.getAttribute(ACCESS_TOKEN);

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		String noteGuid = ParamUtil.getString(resourceRequest, "noteGuid");

		EvernoteAuth evernoteAuth = new EvernoteAuth(EVERNOTE_SERVICE,
				accessToken);
		NoteStoreClient noteStoreClient;
		try {
			noteStoreClient = new ClientFactory(evernoteAuth)
					.createNoteStoreClient();
			Note note = noteStoreClient.getNote(noteGuid, true, true, false,
					false);

			String editNoteURL = EVERNOTE_SERVICE.getHost()
					+ "/shard/s1/view/notebook/" + noteGuid;

			jsonObject.put("noteContent", note.getContent());
			jsonObject.put("guid", noteGuid);
			jsonObject.put("editNoteURL", editNoteURL);
		} catch (EDAMUserException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMUserException.class);
		} catch (EDAMSystemException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMSystemException.class);
		} catch (TException e) {
			LOG.error(e);
			SessionErrors.add(request, TException.class);
		} catch (EDAMNotFoundException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		}

		return jsonObject;
	}

	public void createNote(ActionRequest actionRequest,
			ActionResponse actionResponse) throws IOException, PortletException {
		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(actionRequest);

		PortletSession portletSession = actionRequest.getPortletSession();
		String accessToken = (String) portletSession.getAttribute(ACCESS_TOKEN);

		EvernoteAuth evernoteAuth = new EvernoteAuth(EVERNOTE_SERVICE,
				accessToken);
		NoteStoreClient noteStoreClient;
		try {
			noteStoreClient = new ClientFactory(evernoteAuth)
					.createNoteStoreClient();

			// To create a new note, simply create a new Note object and fill in
			// attributes such as the note's title.
			Note note = new Note();

			String title = ParamUtil.getString(actionRequest, "newNoteTitle");

			// The content of an Evernote note is represented using Evernote
			// Markup Language
			// (ENML). The full ENML specification can be found in the Evernote
			// API
			// Overview at
			// http://dev.evernote.com/documentation/cloud/chapters/ENML.php
			String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
					+ "<!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\">"
					+ "<en-note>"
					+ ParamUtil.getString(actionRequest, "newNoteContent")
					+ "</en-note>";

			String notebookGuid = ParamUtil.getString(actionRequest,
					"newNoteNotebook");

			note.setTitle(title);
			note.setContent(content);
			if (!notebookGuid.isEmpty())
				note.setNotebookGuid(notebookGuid);

			// Finally, send the new note to Evernote using the createNote
			// method
			// The new Note object that is returned will contain
			// server-generated
			// attributes such as the new note's unique GUID.
			Note createdNote = noteStoreClient.createNote(note);
			String newNoteGuid = createdNote.getGuid();

			LOG.info("Successfully created a new note with GUID: "
					+ newNoteGuid);
			actionResponse.setRenderParameter("jspPage", "/jsp/view.jsp");
		} catch (EDAMUserException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMUserException.class);
		} catch (EDAMSystemException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMSystemException.class);
		} catch (TException e) {
			LOG.error(e);
			SessionErrors.add(request, TException.class);
		} catch (EDAMNotFoundException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		}
		if (!SessionErrors.isEmpty(request)) {
			actionResponse.setRenderParameter("jspPage", "/jsp/include/create_note.jsp");
		}

	}

	public void createNotebook(ActionRequest actionRequest,
			ActionResponse actionResponse) throws IOException, PortletException {
		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(actionRequest);

		PortletSession portletSession = actionRequest.getPortletSession();
		String accessToken = (String) portletSession.getAttribute(ACCESS_TOKEN);

		EvernoteAuth evernoteAuth = new EvernoteAuth(EVERNOTE_SERVICE,
				accessToken);
		NoteStoreClient noteStoreClient;
		try {
			noteStoreClient = new ClientFactory(evernoteAuth)
					.createNoteStoreClient();
			String name = ParamUtil.getString(actionRequest, "newNotebookName");

			// To create a new note, simply create a new Note object and fill in
			// attributes such as the note's title.
			Notebook notebook = new Notebook();
			notebook.setName(name);

			Notebook createdNotebook = noteStoreClient.createNotebook(notebook);
			String newNotebookGuid = createdNotebook.getGuid();

			LOG.info("Successfully created a new notebook with GUID: "
					+ newNotebookGuid);
			
		} catch (EDAMUserException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMUserException.class);
		} catch (EDAMSystemException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMSystemException.class);
		} catch (TException e) {
			LOG.error(e);
			SessionErrors.add(request, TException.class);
		}
		// no matter what happens we are going back to create note page
		actionResponse.setRenderParameter("jspPage", "/jsp/include/create_note.jsp");
	}

	public void deleteNote(ActionRequest actionRequest,
			ActionResponse actionResponse) {
		HttpServletRequest request = PortalUtil
				.getHttpServletRequest(actionRequest);

		PortletSession portletSession = actionRequest.getPortletSession();
		String accessToken = (String) portletSession.getAttribute(ACCESS_TOKEN);

		EvernoteAuth evernoteAuth = new EvernoteAuth(EVERNOTE_SERVICE,
				accessToken);
		NoteStoreClient noteStoreClient;
		try {
			noteStoreClient = new ClientFactory(evernoteAuth)
					.createNoteStoreClient();
			String noteGuid = ParamUtil.getString(actionRequest,
					"noteGuidDelete");
			if (!Validator.isNull(noteGuid)) {
				noteStoreClient.deleteNote(noteGuid);

				LOG.info("Successfully deleted note with the GUID: " + noteGuid);
			} else {
				throw new NoNoteException();
			}
			
		} catch (EDAMUserException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMUserException.class);
		} catch (EDAMSystemException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMSystemException.class);
		} catch (TException e) {
			LOG.error(e);
			SessionErrors.add(request, TException.class);
		} catch (EDAMNotFoundException e) {
			LOG.error(e);
			SessionErrors.add(request, EDAMNotFoundException.class);
		} catch (NoNoteException e) {
			LOG.error(e);
			SessionErrors.add(request, NoNoteException.class);
		}
		
		actionResponse.setRenderParameter("jspPage", "/jsp/view.jsp");
	}

	public static void returnJSON(PortletResponse response, Object jsonObj) {
		HttpServletResponse servletResponse = PortalUtil
				.getHttpServletResponse(response);
		PrintWriter pw;
		try {
			pw = servletResponse.getWriter();
			pw.write(jsonObj.toString());
			pw.close();
		} catch (IOException e) {
			LOG.error("Error while returning json", e);
		}
	}

}