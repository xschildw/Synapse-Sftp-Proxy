package org.sagebionetworks.web.server.servlet;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.http.HttpStatus;
import org.sagebionetworks.web.server.servlet.filter.BasicAuthFilter;
import org.sagebionetworks.web.server.servlet.filter.Credentials;
import org.sagebionetworks.web.server.servlet.filter.SFTPFileMetadata;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public class SftpProxyServlet extends HttpServlet {
	public static final String SFTP_CHANNEL_TYPE = "sftp";
	public static final String SFTP_URL_PARAM = "url";
	private static final long serialVersionUID = 1L;
	
	private JSch jsch = new JSch();
	protected static final ThreadLocal<HttpServletRequest> perThreadRequest = new ThreadLocal<HttpServletRequest>();

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		SftpProxyServlet.perThreadRequest.set(request);
		super.service(request, response);
	}
	
	@Override
	public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		super.service(request, response);
	}

	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		ServletOutputStream stream = response.getOutputStream();
		SFTPFileMetadata metadata = SFTPFileMetadata.parseUrl(request.getParameter(SFTP_URL_PARAM));
		Session session = null;
		try {
			session = getSession(request, metadata);
			Channel channel = session.openChannel(SFTP_CHANNEL_TYPE);
			channel.connect();
			ChannelSftp sftpChannel = (ChannelSftp) channel;
			sftpChannel.get(metadata.getSourcePathWithFilename(), stream);
			sftpChannel.exit();
		} catch (SecurityException e) {
			BasicAuthFilter.respondWithChallenge(response, metadata.getHost());
		} catch (JSchException e) {
			throw new ServletException(e);
		} catch (SftpException e) {
			throw new ServletException(e);
		} finally {
			if (session != null)
				session.disconnect();
		}
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		SFTPFileMetadata metadata = SFTPFileMetadata.parseUrl(request.getParameter(SFTP_URL_PARAM));
		try {
			String sftpUrl = sftpUploadFile(request, metadata);
			response.setStatus(HttpStatus.SC_OK);
			//return the path to the client
			response.getOutputStream().write(sftpUrl.getBytes("UTF-8"));
			response.getOutputStream().flush();
		} catch (SecurityException e) {
			BasicAuthFilter.respondWithChallenge(response, metadata.getHost());
		} catch (FileUploadException e) {
			throw new ServletException(e);
		}
	}
	
	public String sftpUploadFile(HttpServletRequest request, SFTPFileMetadata metadata) throws FileUploadException, IOException, ServletException {
		ServletFileUpload upload = new ServletFileUpload();
		FileItemIterator iter = upload.getItemIterator(request);
		if (iter.hasNext()) {
			// should be one in this case
			FileItemStream item = iter.next();
			String name = item.getFieldName();
			InputStream stream = item.openStream();
			
			String fileNameSuffix = item.getName();
			if (fileNameSuffix.contains("\\")) {
				fileNameSuffix = fileNameSuffix.substring(fileNameSuffix.lastIndexOf("\\") + 1);
			}
			
			Session session = null;
			try {
				session = getSession(request, metadata);
				Channel channel = session.openChannel(SFTP_CHANNEL_TYPE);
				channel.connect();
				ChannelSftp sftpChannel = (ChannelSftp) channel;
				changeToRemoteUploadDirectory(metadata, sftpChannel);
				sftpChannel.put(stream, metadata.getFilename() + fileNameSuffix);
				sftpChannel.exit();
				
				return metadata.getFullUrl() + fileNameSuffix;
			} catch (SecurityException e) {
				throw e;
			} catch (JSchException e) {
				throw new ServletException(e);
			} catch (SftpException e) {
				throw new ServletException(e);
			} finally {
				if (session != null)
					session.disconnect();
			}
		}
		return null;
	}
	
	public void changeToRemoteUploadDirectory(SFTPFileMetadata metadata, ChannelSftp sftpChannel) throws SftpException {
		//change directory (and make directory if not exist)
		for (String directory : metadata.getPath()) {
			try{
				sftpChannel.cd(directory);
			} catch (SftpException e) {
				//cannot access, try to create and go there
				sftpChannel.mkdir(directory);
				sftpChannel.cd(directory);
			}
		}
	}
	
	public Session getSession(HttpServletRequest request, SFTPFileMetadata metadata) throws SecurityException {
		Session session;
		try {
			Credentials credentials = BasicAuthFilter.getCredentials(request);
			if (credentials == null) {
				throw new IllegalArgumentException("Basic authorization required for SFTP connection.");
			}
			session = jsch.getSession(credentials.getUsername(), metadata.getHost(), 22);
			session.setPassword(credentials.getPassword());
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect();
		} catch (Throwable e) {
			throw new SecurityException(e);
		}
		return session;
	}

	/**
	 * For testing purposes
	 * @param jsch
	 */
	public void setJsch(JSch jsch) {
		this.jsch = jsch;
	}

}
